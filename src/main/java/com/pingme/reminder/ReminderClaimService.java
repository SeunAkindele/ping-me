package com.pingme.reminder;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stage 5/6 — Claims due reminders and enqueues jobs for the notification worker.
 *
 * <p>This is the <b>producer</b> side of the queue. It runs inside the scheduler tick and does
 * two things atomically (same database transaction):
 *
 * <ol>
 *   <li>Moves due reminders from {@link ReminderStatus#PENDING} to
 *       {@link ReminderStatus#PROCESSING}.</li>
 *   <li>Creates a {@link ReminderJob} (status {@code QUEUED}) for each claimed reminder.</li>
 * </ol>
 *
 * <p>Both happen in one transaction so they commit or roll back together. A reminder can never
 * be stuck in PROCESSING without a job to deliver it.
 *
 * <h3>Why the scheduler does not send notifications itself</h3>
 *
 * <p>The scheduler's job is to <em>decide what is due</em>. That is a fast index scan on
 * PostgreSQL — milliseconds even with millions of rows. Sending a notification involves
 * network I/O (SMTP, HTTP, push gateways) which is slow and can fail. If the scheduler
 * also sent notifications:
 *
 * <ul>
 *   <li>A slow email provider would block the scan for all users.</li>
 *   <li>A failed send would hold row locks, delaying the next batch.</li>
 *   <li>You could not scale sending independently of scanning.</li>
 * </ul>
 *
 * <p>By writing a job into the queue, the scheduler finishes instantly and the worker takes over.
 * This is the fundamental producer/consumer separation behind every real queue system
 * (RabbitMQ, SQS, Kafka).
 *
 * <h3>Isolation level</h3>
 *
 * <p>{@code READ COMMITTED} is used on purpose. The claim must observe another scheduler's
 * committed status change. A {@code REPEATABLE READ} snapshot would keep seeing those rows
 * as pending, leading to duplicate claims.
 */
@Service
public class ReminderClaimService {

	private static final Logger log = LoggerFactory.getLogger(ReminderClaimService.class);

	private final ReminderClaimRepository reminderClaimRepository;
	private final ReminderJobRepository reminderJobRepository;

	/**
	 * Stage 6 change: this service now depends on {@link ReminderJobRepository} in addition to
	 * {@link ReminderClaimRepository}. The job repository is needed to create queue entries
	 * as part of the claim transaction.
	 */
	public ReminderClaimService(
			ReminderClaimRepository reminderClaimRepository,
			ReminderJobRepository reminderJobRepository) {
		this.reminderClaimRepository = reminderClaimRepository;
		this.reminderJobRepository = reminderJobRepository;
	}

	/**
	 * Claims due reminders and enqueues jobs for the notification worker.
	 *
	 * <p>This single transaction:
	 * <ol>
	 *   <li>Locks PENDING reminders where {@code scheduled_time <= now}
	 *       ({@code FOR UPDATE SKIP LOCKED} — see {@link ReminderClaimRepository}).</li>
	 *   <li>Marks each reminder as PROCESSING (so the next scheduler tick skips them).</li>
	 *   <li>Creates a QUEUED {@link ReminderJob} for each (so the worker knows to process them).</li>
	 * </ol>
	 *
	 * <p>If this transaction rolls back (e.g., database error), the reminders stay PENDING and
	 * the next tick can try again. No jobs are orphaned because both operations are atomic.
	 *
	 * @param now       the current time; injected for testability rather than calling Instant.now()
	 * @param batchSize maximum reminders to claim in one tick
	 * @return number of reminders claimed and enqueued
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public int claimDueReminders(Instant now, int batchSize) {
		Objects.requireNonNull(now, "now is required");
		if (batchSize < 1) {
			throw new IllegalArgumentException("batchSize must be at least 1");
		}

		List<Reminder> due = reminderClaimRepository.lockDueReminders(now, batchSize);
		int claimed = 0;
		for (Reminder reminder : due) {
			if (!reminder.isDue(now)) {
				continue;
			}

			// Stage 5: mark the reminder so the scheduler's next tick skips it.
			reminder.markProcessing();

			// Stage 6: enqueue a job for the worker to process.
			// The job starts as QUEUED — it is a message sitting in the queue.
			// The worker will claim it, send the notification, and mark it COMPLETED.
			reminderJobRepository.save(ReminderJob.create(reminder));

			claimed++;
		}

		if (claimed > 0) {
			log.info("Claimed {} due reminder(s): marked PROCESSING and enqueued jobs", claimed);
		}
		else {
			log.debug("No due reminders to claim at {}", now);
		}
		return claimed;
	}

}
