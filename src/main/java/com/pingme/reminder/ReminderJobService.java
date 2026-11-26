package com.pingme.reminder;

import com.pingme.notification.NotificationSender;
import com.pingme.notification.PermanentNotificationException;
import com.pingme.notification.ReminderNotification;
import com.pingme.reminder.event.ReminderSentEvent;
import com.pingme.user.User;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stage 6/7 — The consumer side of the queue: claims jobs, sends notifications, records results,
 * and (Stage 7) decides what happens when a send fails.
 *
 * <h3>Producer / Consumer in this system</h3>
 *
 * <p>The scheduler ({@link ReminderClaimService}) is the <b>producer</b>: it finds due reminders
 * and inserts {@link ReminderJob} rows into the queue. This service is the <b>consumer</b>: it
 * reads jobs from the queue and does the actual work (sending notifications).
 *
 * <h3>Transaction design: why claiming and processing are separate</h3>
 *
 * <p>{@link #claimQueuedJobs(Instant, int)} and {@link #processJob(Long, Instant)} run in
 * <b>separate transactions</b> on purpose:
 *
 * <ul>
 *   <li>{@code claimQueuedJobs} — <b>fast:</b> lock rows, mark PROCESSING, commit, release locks.</li>
 *   <li>{@code processJob} — <b>slow:</b> load job, send notification (network I/O), update
 *       status, commit. Each job runs in its own transaction.</li>
 * </ul>
 *
 * <p>If they shared one big transaction, a slow notification would hold row locks for seconds
 * or minutes, blocking other workers. The cost of separating them is that a crash between the two
 * leaves a job in PROCESSING with nobody working on it. Stage 7's
 * {@link #recoverStuckJobs(Instant, Duration, int)} closes that gap.
 *
 * <h3>Stage 7 — the retry decision</h3>
 *
 * <pre>
 *   send()
 *    ├─ succeeds ──────────────────────────────→ job COMPLETED, reminder SENT
 *    ├─ PermanentNotificationException ────────→ job FAILED (DLQ), reminder FAILED
 *    └─ TransientNotificationException
 *       or any unexpected RuntimeException
 *        ├─ attempts left (RetryPolicy) ───────→ job QUEUED, nextAttemptAt = now + backoff,
 *        │                                       reminder stays PROCESSING
 *        └─ attempts exhausted ────────────────→ job FAILED (DLQ), reminder FAILED
 * </pre>
 *
 * <p>Unexpected exceptions are deliberately treated as <em>transient</em>. A bug or an
 * unclassified provider error is not proof the message can never be delivered, and
 * {@code maxAttempts} still bounds the damage. Treating them as permanent would silently drop
 * notifications every time an adapter forgot to classify an error.
 *
 * <h3>Delivery guarantee: at-least-once</h3>
 *
 * <p>Retries plus crash recovery mean a reminder is delivered <em>at least once</em>, not
 * <em>exactly once</em>. Example: the provider accepts the email, then the database commit
 * fails. The job is retried and the user gets a second email. Exactly-once delivery across a
 * database and an external provider is not achievable in general; real systems get close by
 * sending an <b>idempotency key</b> (for example the job id) so the provider can drop duplicates.
 *
 * <h3>Stage 8 — announcing success</h3>
 *
 * <p>When a job completes, this service publishes a {@link ReminderSentEvent}. The queue still
 * drives delivery; the event only lets other modules react to a delivery that has been recorded
 * (see the event's documentation for why events must not replace the queue).
 */
@Service
public class ReminderJobService {

	private static final Logger log = LoggerFactory.getLogger(ReminderJobService.class);

	private final ReminderJobClaimRepository reminderJobClaimRepository;
	private final ReminderJobRepository reminderJobRepository;
	private final NotificationSender notificationSender;
	private final RetryPolicy retryPolicy;
	private final ApplicationEventPublisher eventPublisher;

	/**
	 * Stage 7 change: {@link RetryPolicy} is injected like any other dependency. The service asks
	 * the policy questions ("can this job retry?", "how long should it wait?") but never hard-codes
	 * the answers, so retry behaviour is tuned in configuration rather than in code.
	 *
	 * <p>Stage 8 change: {@link ApplicationEventPublisher} is injected to announce deliveries.
	 */
	public ReminderJobService(
			ReminderJobClaimRepository reminderJobClaimRepository,
			ReminderJobRepository reminderJobRepository,
			NotificationSender notificationSender,
			RetryPolicy retryPolicy,
			ApplicationEventPublisher eventPublisher) {
		this.reminderJobClaimRepository = reminderJobClaimRepository;
		this.reminderJobRepository = reminderJobRepository;
		this.notificationSender = notificationSender;
		this.retryPolicy = retryPolicy;
		this.eventPublisher = eventPublisher;
	}

	/**
	 * Claims a batch of QUEUED jobs that are due for an attempt by moving them to PROCESSING.
	 *
	 * <p>Stage 7: a job that is backing off ({@code nextAttemptAt > now}) is skipped by the query
	 * itself. Each claimed job spends one attempt here, before any send is tried.
	 *
	 * <p>Uses {@code READ_COMMITTED} isolation so this transaction sees jobs that other
	 * transactions (e.g., the scheduler) have already committed.
	 *
	 * @param now       current time; drives both eligibility and the processing lease start
	 * @param batchSize maximum jobs to claim in one tick
	 * @return IDs of claimed jobs; the worker processes each one individually
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public List<Long> claimQueuedJobs(Instant now, int batchSize) {
		Objects.requireNonNull(now, "now is required");
		if (batchSize < 1) {
			throw new IllegalArgumentException("batchSize must be at least 1");
		}

		List<ReminderJob> jobs = reminderJobClaimRepository.lockQueuedJobs(now, batchSize);
		for (ReminderJob job : jobs) {
			job.markProcessing(now);
		}

		if (!jobs.isEmpty()) {
			log.info("Claimed {} queued job(s) for processing", jobs.size());
		}
		else {
			log.debug("No queued jobs due at {}", now);
		}

		// Return IDs, not entities. After this transaction commits, the entities are detached.
		// The worker will load each job fresh in its own transaction.
		return jobs.stream().map(ReminderJob::getId).toList();
	}

	/**
	 * Processes a single claimed job: sends the notification and records the outcome.
	 *
	 * <p>Each job runs in its own transaction so that a failure on job #3 does not roll back
	 * the successful delivery of jobs #1 and #2.
	 *
	 * <p>Only the {@code send} call sits inside the {@code try}. If marking the job COMPLETED
	 * threw (a programming error), we must not misread that as a delivery failure and schedule a
	 * retry of a notification that was in fact delivered.
	 *
	 * <p>Exceptions from the sender are caught here and turned into state changes, so they never
	 * cross the transactional boundary. The transaction therefore commits normally, persisting
	 * the retry schedule or dead-letter status together with {@code lastError}.
	 *
	 * @param jobId the ID of a job previously claimed by {@link #claimQueuedJobs(Instant, int)}
	 * @param now   current time; used for timestamps and backoff calculation
	 */
	@Transactional
	public void processJob(Long jobId, Instant now) {
		Objects.requireNonNull(jobId, "jobId is required");
		Objects.requireNonNull(now, "now is required");

		// Load job + reminder + user in a single query (JOIN FETCH) to minimize round trips.
		ReminderJob job = reminderJobRepository.findByIdWithReminderAndUser(jobId)
				.orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));

		// Idempotency guard: between the claim and now, stuck-job recovery may already have
		// requeued or dead-lettered this job. Only the holder of a PROCESSING job may send.
		if (job.getStatus() != ReminderJobStatus.PROCESSING) {
			log.warn("Skipping job {}: expected PROCESSING but found {}", jobId, job.getStatus());
			return;
		}

		Reminder reminder = job.getReminder();
		User user = reminder.getUser();

		// Build the notification payload — decoupled from JPA entities.
		ReminderNotification notification = new ReminderNotification(
				user.getEmail(),
				user.getName(),
				reminder.getTitle(),
				reminder.getDescription(),
				reminder.getScheduledTime());

		try {
			notificationSender.send(notification);
		}
		catch (PermanentNotificationException ex) {
			// Retrying cannot help. Skip the retry policy entirely.
			deadLetter(job, reminder, describe(ex), now);
			return;
		}
		catch (RuntimeException ex) {
			// Covers TransientNotificationException and anything unclassified. See class docs
			// for why unknown errors are retried rather than dropped.
			retryOrDeadLetter(job, reminder, describe(ex), now);
			return;
		}

		// Success: both updates are in this transaction — atomic commit.
		job.markCompleted(now);
		reminder.markSent();

		log.info("Job {} completed on attempt {}: reminder {} sent to {}",
				jobId, job.getAttemptCount(), reminder.getId(), user.getEmail());

		// Stage 8: announce the delivery. Listeners are AFTER_COMMIT, so they only run once the
		// COMPLETED/SENT state above is durable. If this commit fails, the event is discarded and
		// the job is retried by recovery, which publishes again on its successful attempt.
		eventPublisher.publishEvent(new ReminderSentEvent(
				reminder.getId(),
				job.getId(),
				user.getId(),
				user.getEmail(),
				reminder.getTitle(),
				job.getAttemptCount(),
				now));
	}

	/**
	 * Stage 7 — Returns jobs stuck in PROCESSING to the queue (or the DLQ).
	 *
	 * <p>A job is "stuck" when it was claimed longer ago than {@code processingTimeout} and is
	 * still PROCESSING. The claiming worker almost certainly died after the claim committed:
	 * a crash, an OOM kill, a redeploy. Each stuck job is treated exactly like a transient
	 * failure: requeued with backoff if it has attempts left, dead-lettered otherwise. Because the
	 * attempt was counted at claim time, a job that crashes the worker every time cannot loop
	 * forever — it reaches {@code maxAttempts} and is parked.
	 *
	 * <p>The timeout is a trade-off. If a send is merely slow, not dead, recovery may requeue it
	 * while it is still running, and the user can receive two notifications. That is the
	 * at-least-once guarantee in action; {@link #processJob(Long, Instant)} limits the overlap by
	 * refusing to send for a job that is no longer PROCESSING.
	 *
	 * @param now               current time
	 * @param processingTimeout how long a claim may last before it is considered abandoned
	 * @param batchSize         maximum stuck jobs to recover in one call
	 * @return number of jobs recovered
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public int recoverStuckJobs(Instant now, Duration processingTimeout, int batchSize) {
		Objects.requireNonNull(now, "now is required");
		Objects.requireNonNull(processingTimeout, "processingTimeout is required");

		List<ReminderJob> stuck = reminderJobClaimRepository.lockStuckJobs(now.minus(processingTimeout), batchSize);
		for (ReminderJob job : stuck) {
			String error = "Processing timed out: no result within %s of claim at %s"
					.formatted(processingTimeout, job.getStartedAt());
			// Lazy-loads the reminder; fine here because we are inside the transaction.
			retryOrDeadLetter(job, job.getReminder(), error, now);
		}

		if (!stuck.isEmpty()) {
			log.warn("Recovered {} job(s) stuck in PROCESSING for longer than {}", stuck.size(), processingTimeout);
		}
		return stuck.size();
	}

	/**
	 * Applies the {@link RetryPolicy} to a transient failure.
	 *
	 * <p>The backoff is computed from the attempts already made: with the defaults, a job that
	 * just failed its 1st attempt waits 30s, after the 2nd it waits 60s, and after the 3rd it has
	 * no attempts left and is dead-lettered.
	 */
	private void retryOrDeadLetter(ReminderJob job, Reminder reminder, String error, Instant now) {
		int attempts = job.getAttemptCount();
		if (!retryPolicy.canRetry(attempts)) {
			deadLetter(job, reminder, "Gave up after %d attempt(s). Last error: %s".formatted(attempts, error), now);
			return;
		}

		Duration backoff = retryPolicy.backoffAfter(attempts);
		Instant retryAt = now.plus(backoff);
		job.scheduleRetry(error, retryAt, now);

		// WARN, not ERROR: a scheduled retry is the system working as designed.
		log.warn("Job {} attempt {}/{} failed: {}. Retrying in {} (at {})",
				job.getId(), attempts, retryPolicy.maxAttempts(), error, backoff, retryAt);
	}

	/**
	 * Moves the job to the dead letter queue and marks the reminder FAILED so the user-facing
	 * API ({@code GET /reminders/{id}}) reports the truth instead of leaving it PROCESSING forever.
	 */
	private void deadLetter(ReminderJob job, Reminder reminder, String error, Instant now) {
		job.markFailed(error, now);
		reminder.markFailed();

		// ERROR: someone should look at this. In production this log line (or a metric on the
		// number of FAILED jobs) would feed an alert.
		log.error("Job {} dead-lettered after {} attempt(s); reminder {} marked FAILED: {}",
				job.getId(), job.getAttemptCount(), reminder.getId(), error);
	}

	/**
	 * Builds a compact, human-readable error string: exception type plus message. The type
	 * matters when the message alone is vague (for example {@code null} or "Connection reset").
	 */
	private static String describe(Throwable ex) {
		String message = ex.getMessage();
		return message == null
				? ex.getClass().getSimpleName()
				: ex.getClass().getSimpleName() + ": " + message;
	}

}
