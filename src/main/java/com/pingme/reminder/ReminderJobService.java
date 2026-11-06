package com.pingme.reminder;

import com.pingme.notification.NotificationException;
import com.pingme.notification.NotificationSender;
import com.pingme.notification.ReminderNotification;
import com.pingme.user.User;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stage 6 — The consumer side of the queue: claims jobs, sends notifications, records results.
 *
 * <h3>Producer / Consumer in this system</h3>
 *
 * <p>The scheduler ({@link ReminderClaimService}) is the <b>producer</b>: it finds due reminders
 * and inserts {@link ReminderJob} rows into the queue. This service is the <b>consumer</b>: it
 * reads jobs from the queue and does the actual work (sending notifications).
 *
 * <p>This separation exists in every real queue system:
 * <ul>
 *   <li>RabbitMQ: publisher sends a message → consumer receives and acks it.</li>
 *   <li>SQS: producer sends a message → consumer receives, processes, and deletes it.</li>
 *   <li>Kafka: producer writes to a topic → consumer reads from the partition.</li>
 * </ul>
 *
 * <p>Here PostgreSQL is the queue, but the pattern is identical:
 * <ol>
 *   <li>Producer writes a message → {@code INSERT} a QUEUED job.</li>
 *   <li>Consumer reads the message → {@code SELECT + FOR UPDATE SKIP LOCKED}.</li>
 *   <li>Consumer acknowledges → {@code UPDATE} status to COMPLETED.</li>
 *   <li>Consumer rejects → {@code UPDATE} status to FAILED.</li>
 * </ol>
 *
 * <h3>Transaction design: why claiming and processing are separate</h3>
 *
 * <p>{@link #claimQueuedJobs(int)} and {@link #processJob(Long)} run in <b>separate transactions</b>
 * on purpose:
 *
 * <ul>
 *   <li>{@code claimQueuedJobs} — <b>fast:</b> lock rows, mark PROCESSING, commit, release locks.
 *       The row locks are held only for the duration of this short transaction.</li>
 *   <li>{@code processJob} — <b>slow:</b> load job, send notification (network I/O), update
 *       status, commit. Each job runs in its own transaction.</li>
 * </ul>
 *
 * <p>If they shared one big transaction, a slow notification would hold row locks for seconds
 * or minutes, blocking other workers. Separate transactions mean the claim is instant and the
 * lock window is brief.
 *
 * <p><b>Trade-off:</b> if the process crashes after claiming but before processing, jobs are
 * stuck in PROCESSING with no one working on them. Stage 7 adds a recovery mechanism that
 * reclaims stuck jobs after a timeout.
 */
@Service
public class ReminderJobService {

	private static final Logger log = LoggerFactory.getLogger(ReminderJobService.class);

	private final ReminderJobClaimRepository reminderJobClaimRepository;
	private final ReminderJobRepository reminderJobRepository;
	private final NotificationSender notificationSender;

	public ReminderJobService(
			ReminderJobClaimRepository reminderJobClaimRepository,
			ReminderJobRepository reminderJobRepository,
			NotificationSender notificationSender) {
		this.reminderJobClaimRepository = reminderJobClaimRepository;
		this.reminderJobRepository = reminderJobRepository;
		this.notificationSender = notificationSender;
	}

	/**
	 * Claims a batch of QUEUED jobs by moving them to PROCESSING.
	 *
	 * <p>Uses {@code READ_COMMITTED} isolation so this transaction sees jobs that other
	 * transactions (e.g., the scheduler) have already committed. With {@code REPEATABLE_READ},
	 * a snapshot taken at transaction start might miss newly inserted jobs.
	 *
	 * <p>{@code FOR UPDATE SKIP LOCKED} inside the claim repository prevents two workers from
	 * claiming the same job — see {@link ReminderJobClaimRepository} for the full explanation.
	 *
	 * @param batchSize maximum jobs to claim in one tick
	 * @return IDs of claimed jobs; the worker processes each one individually
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public List<Long> claimQueuedJobs(int batchSize) {
		if (batchSize < 1) {
			throw new IllegalArgumentException("batchSize must be at least 1");
		}

		List<ReminderJob> jobs = reminderJobClaimRepository.lockQueuedJobs(batchSize);
		for (ReminderJob job : jobs) {
			job.markProcessing();
		}

		if (!jobs.isEmpty()) {
			log.info("Claimed {} queued job(s) for processing", jobs.size());
		}
		else {
			log.debug("No queued jobs to claim");
		}

		// Return IDs, not entities. After this transaction commits, the entities are detached.
		// The worker will load each job fresh in its own transaction.
		return jobs.stream().map(ReminderJob::getId).toList();
	}

	/**
	 * Processes a single claimed job: sends the notification, updates both the job and the
	 * reminder to their final states.
	 *
	 * <p>Each job runs in its own transaction so that a failure on job #3 does not roll back
	 * the successful delivery of jobs #1 and #2. This is how real queue consumers work: each
	 * message is acknowledged independently.
	 *
	 * <p><b>On success:</b> job → COMPLETED, reminder → SENT. Both updates happen in the same
	 * transaction: either both commit or neither does. The user sees "SENT" only when the
	 * notification was actually delivered.
	 *
	 * <p><b>On failure:</b> job → FAILED. The reminder stays in PROCESSING (it was not sent).
	 * Stage 7 adds retry logic that can reclaim failed jobs.
	 *
	 * @param jobId the ID of a job in PROCESSING state
	 */
	@Transactional
	public void processJob(Long jobId) {
		Objects.requireNonNull(jobId, "jobId is required");

		// Load job + reminder + user in a single query (JOIN FETCH) to minimize round trips.
		ReminderJob job = reminderJobRepository.findByIdWithReminderAndUser(jobId)
				.orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));

		Reminder reminder = job.getReminder();
		User user = reminder.getUser();

		// Build the notification payload — decoupled from JPA entities.
		// The sender never sees a Hibernate proxy or a database entity.
		ReminderNotification notification = new ReminderNotification(
				user.getEmail(),
				user.getName(),
				reminder.getTitle(),
				reminder.getDescription(),
				reminder.getScheduledTime());

		try {
			// Attempt to send the notification via the configured sender.
			// Right now this is ConsoleNotificationSender (logs to console).
			// In production, this would be an SMTP call, a push notification, etc.
			notificationSender.send(notification);

			// Success: mark both job and reminder as complete.
			// Both updates are in this transaction — atomic commit.
			job.markCompleted();
			reminder.markSent();

			log.info("Job {} completed: reminder {} sent to {}",
					jobId, reminder.getId(), user.getEmail());

		}
		catch (NotificationException ex) {
			// The notification failed. Mark the job but do NOT mark the reminder as SENT.
			// The reminder stays in PROCESSING because it was not actually delivered.
			// Stage 7 adds retry logic: the worker can try again with backoff.
			job.markFailed();

			log.error("Job {} failed for reminder {}: {}",
					jobId, reminder.getId(), ex.getMessage());
		}
	}

}
