package com.pingme.reminder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * Stage 6/7 — A unit of work in the database-backed queue.
 *
 * <p>In production systems, a "job" or "message" lives in RabbitMQ, SQS, or Kafka.
 * Here the same concept lives in a PostgreSQL table so you learn the pattern without
 * extra infrastructure. The table is the queue. Each row is a message.
 *
 * <h3>Why separate jobs from reminders?</h3>
 *
 * <p>A {@link Reminder} is a <b>business entity</b> — it belongs to a user and has a title,
 * description, and scheduled time. A {@code ReminderJob} is an <b>operational artifact</b> —
 * it tracks the work of delivering that reminder. Separating them means:
 *
 * <ul>
 *   <li>The reminder table stays clean: no processing columns, no worker metadata.</li>
 *   <li>The job table is purpose-built for the worker: fast scans, independent lifecycle.</li>
 *   <li>Stage 7 adds retry fields ({@code attempt_count}, {@code last_error},
 *       {@code next_attempt_at}, {@code started_at}) here without touching the reminders table.</li>
 *   <li>You get an audit trail: the job row records attempts, the last error, and timings.</li>
 * </ul>
 *
 * <h3>Lifecycle (Stage 7)</h3>
 *
 * <pre>
 *   Scheduler claims reminder   → job created (QUEUED, attempts = 0, nextAttemptAt = now)
 *   Worker claims job           → PROCESSING, attempts + 1, startedAt = now
 *   Send succeeds               → COMPLETED;  reminder → SENT
 *   Transient failure, attempts left
 *                               → QUEUED again, lastError set, nextAttemptAt = now + backoff
 *   Permanent failure, or no attempts left
 *                               → FAILED (dead letter); reminder → FAILED
 * </pre>
 *
 * <h3>Why the entity, not the service, owns these transitions</h3>
 *
 * <p>Each method below changes several fields that must stay consistent with each other (for
 * example, a retry must set status, error, and next attempt time together). Keeping that inside
 * the entity means no caller can do half of it — such as moving a job back to QUEUED but
 * forgetting to push {@code nextAttemptAt} forward, which would cause an instant retry loop.
 *
 * <p>Time is passed in as a parameter rather than read with {@code Instant.now()} inside the
 * entity. That keeps the domain deterministic and lets tests control the clock.
 *
 * <h3>JPA notes</h3>
 *
 * <p>{@code @ManyToOne(fetch = FetchType.LAZY)} means Hibernate does <b>not</b> load the
 * reminder when you load the job. It creates a proxy object instead. The actual SELECT fires
 * only when you call {@code getReminder()} inside an active transaction. This avoids loading
 * the full reminder + user graph for every job during a batch claim — we only need to change
 * the job's own fields during claiming.
 */
@Entity
@Table(name = "reminder_jobs")
public final class ReminderJob {

	/**
	 * Upper bound on the stored error message. Exception messages can be arbitrarily large (stack
	 * dumps, full HTTP response bodies). Capping keeps rows small and stops one noisy provider
	 * from bloating the table.
	 */
	static final int MAX_ERROR_LENGTH = 1000;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/**
	 * The reminder this job will deliver. {@code updatable = false} because a job is permanently
	 * bound to one reminder — you never reassign a job to a different reminder.
	 */
	@ManyToOne(optional = false, fetch = FetchType.LAZY)
	@JoinColumn(name = "reminder_id", nullable = false, updatable = false)
	private Reminder reminder;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ReminderJobStatus status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	/**
	 * Timestamp when the job reached a terminal state (COMPLETED or FAILED). Null while the job
	 * is still QUEUED or PROCESSING, including while it waits for a retry.
	 */
	@Column(name = "processed_at")
	private Instant processedAt;

	/**
	 * Stage 7 — Number of times a worker has claimed this job. Incremented on <em>claim</em>,
	 * not on failure: if the JVM dies mid-send, that attempt still counts. Otherwise a job that
	 * reliably crashes the worker would be retried forever.
	 */
	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	/**
	 * Stage 7 — Reason the most recent attempt failed. Kept after a later success too, as a
	 * record that the job needed retries. On a FAILED job it explains why it was dead-lettered.
	 */
	@Column(name = "last_error")
	private String lastError;

	/**
	 * Stage 7 — Earliest time a worker may claim this job. Equal to {@code createdAt} for a new
	 * job; moved into the future by each retry. Exponential backoff lives entirely in this field.
	 */
	@Column(name = "next_attempt_at", nullable = false)
	private Instant nextAttemptAt;

	/**
	 * Stage 7 — When the current attempt was claimed. Used to detect jobs stuck in PROCESSING
	 * after a crash. Cleared when the job leaves PROCESSING.
	 */
	@Column(name = "started_at")
	private Instant startedAt;

	protected ReminderJob() {
		// Required by JPA for reflection-based instantiation; use the factory method instead.
	}

	private ReminderJob(Reminder reminder, Instant createdAt) {
		this.reminder = Objects.requireNonNull(reminder, "reminder is required");
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
		this.status = ReminderJobStatus.QUEUED;
		this.attemptCount = 0;
		this.nextAttemptAt = createdAt;
	}

	/**
	 * Factory: creates a new job for a claimed reminder. Status starts at {@code QUEUED} and the
	 * job is eligible immediately ({@code nextAttemptAt = createdAt}). This is what the scheduler
	 * calls after marking a reminder as PROCESSING.
	 */
	public static ReminderJob create(Reminder reminder) {
		return new ReminderJob(reminder, Instant.now());
	}

	/**
	 * A worker has claimed this job: QUEUED → PROCESSING.
	 *
	 * <p>Stage 7: this is where an attempt is "spent". The counter goes up before any network
	 * call is made, and {@code startedAt} marks the start of the processing lease.
	 *
	 * @param now the claim time
	 */
	public void markProcessing(Instant now) {
		Objects.requireNonNull(now, "now is required");
		transitionTo(ReminderJobStatus.PROCESSING);
		this.attemptCount++;
		this.startedAt = now;
	}

	/**
	 * The notification was delivered: PROCESSING → COMPLETED. In queue terms, the consumer acks
	 * the message.
	 *
	 * @param now the completion time
	 */
	public void markCompleted(Instant now) {
		Objects.requireNonNull(now, "now is required");
		transitionTo(ReminderJobStatus.COMPLETED);
		this.processedAt = now;
		this.startedAt = null;
	}

	/**
	 * Stage 7 — A transient failure with attempts remaining: PROCESSING → QUEUED.
	 *
	 * <p>The job goes back into the queue but is invisible to workers until {@code retryAt}.
	 * In queue terms, this is a nack with requeue plus a delay.
	 *
	 * @param error   why this attempt failed
	 * @param retryAt when the job becomes eligible again; must not be in the past relative to
	 *                {@code now}, or the backoff would be meaningless
	 * @param now     the failure time
	 */
	public void scheduleRetry(String error, Instant retryAt, Instant now) {
		Objects.requireNonNull(retryAt, "retryAt is required");
		Objects.requireNonNull(now, "now is required");
		if (retryAt.isBefore(now)) {
			throw new IllegalArgumentException("retryAt must not be before now");
		}
		transitionTo(ReminderJobStatus.QUEUED);
		this.lastError = truncate(error);
		this.nextAttemptAt = retryAt;
		this.startedAt = null;
	}

	/**
	 * Stage 7 — Give up on this job: PROCESSING → FAILED. The job is now in the dead letter
	 * queue and will not be retried automatically.
	 *
	 * <p>Called for permanent errors and for transient errors once retries are exhausted.
	 *
	 * @param error why the job was abandoned
	 * @param now   the failure time
	 */
	public void markFailed(String error, Instant now) {
		Objects.requireNonNull(now, "now is required");
		transitionTo(ReminderJobStatus.FAILED);
		this.lastError = truncate(error);
		this.processedAt = now;
		this.startedAt = null;
	}

	public Long getId() {
		return id;
	}

	public Reminder getReminder() {
		return reminder;
	}

	public Long getReminderId() {
		return reminder.getId();
	}

	public ReminderJobStatus getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getProcessedAt() {
		return processedAt;
	}

	public int getAttemptCount() {
		return attemptCount;
	}

	public String getLastError() {
		return lastError;
	}

	public Instant getNextAttemptAt() {
		return nextAttemptAt;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	private void transitionTo(ReminderJobStatus next) {
		if (!status.canTransitionTo(next)) {
			throw new IllegalStateException(
					"Cannot move job from %s to %s".formatted(status, next));
		}
		this.status = next;
	}

	private static String truncate(String error) {
		if (error == null || error.isBlank()) {
			return "Unknown error";
		}
		return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof ReminderJob job)) {
			return false;
		}
		return id != null && id.equals(job.id);
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

	@Override
	public String toString() {
		return "ReminderJob{id=%s, reminderId=%s, status=%s, attempts=%d}"
				.formatted(id, getReminderId(), status, attemptCount);
	}

}
