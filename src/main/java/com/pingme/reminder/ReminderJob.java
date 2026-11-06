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
 * Stage 6 — A unit of work in the database-backed queue.
 *
 * <p>In production systems, a "job" or "message" lives in RabbitMQ, SQS, or Kafka.
 * Here the same concept lives in a PostgreSQL table so you learn the pattern without
 * extra infrastructure. The table is the queue. Each row is a message.
 *
 * <h3>Why separate jobs from reminders?</h3>
 *
 * <p>A {@link Reminder} is a <b>business entity</b> — it belongs to a user and has a title,
 * description, and scheduled time. A {@code ReminderJob} is an <b>operational artifact</b> —
 * it represents one attempt to deliver that reminder. Separating them means:
 *
 * <ul>
 *   <li>The reminder table stays clean: no processing columns, no worker metadata.</li>
 *   <li>The job table is purpose-built for the worker: fast scans, independent lifecycle.</li>
 *   <li>Stage 7 can add retry fields ({@code attempt_count}, {@code last_error}) to jobs
 *       without touching the reminders table.</li>
 *   <li>You get an audit trail: the job row records when processing started and finished.</li>
 * </ul>
 *
 * <h3>Lifecycle</h3>
 *
 * <pre>
 *   Scheduler claims reminder → creates job (QUEUED)
 *   Worker claims job         → (QUEUED → PROCESSING)
 *   Worker sends notification → (PROCESSING → COMPLETED), reminder (PROCESSING → SENT)
 *   Worker fails              → (PROCESSING → FAILED)
 * </pre>
 *
 * <h3>JPA notes</h3>
 *
 * <p>{@code @ManyToOne(fetch = FetchType.LAZY)} means Hibernate does <b>not</b> load the
 * reminder when you load the job. It creates a proxy object instead. The actual SELECT fires
 * only when you call {@code getReminder()} inside an active transaction. This avoids loading
 * the full reminder + user graph for every job during a batch claim — we only need to change
 * the job's status field during claiming.
 */
@Entity
@Table(name = "reminder_jobs")
public final class ReminderJob {

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
	 * Timestamp when the worker finished processing (success or failure). Null while the job
	 * is still QUEUED or PROCESSING.
	 */
	@Column(name = "processed_at")
	private Instant processedAt;

	protected ReminderJob() {
		// Required by JPA for reflection-based instantiation; use the factory method instead.
	}

	private ReminderJob(Long id, Reminder reminder, ReminderJobStatus status,
			Instant createdAt, Instant processedAt) {
		this.id = id;
		this.reminder = Objects.requireNonNull(reminder, "reminder is required");
		this.status = Objects.requireNonNull(status, "status is required");
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
		this.processedAt = processedAt;
	}

	/**
	 * Factory: creates a new job for a claimed reminder. Status starts at {@code QUEUED} — the job
	 * is sitting in the queue, waiting for a worker to pick it up. This is what the scheduler
	 * calls after marking a reminder as PROCESSING.
	 */
	public static ReminderJob create(Reminder reminder) {
		return new ReminderJob(null, reminder, ReminderJobStatus.QUEUED, Instant.now(), null);
	}

	/**
	 * A worker has claimed this job. Moves from QUEUED to PROCESSING.
	 * In queue terms: the consumer has dequeued the message but hasn't acknowledged it yet.
	 */
	public void markProcessing() {
		transitionTo(ReminderJobStatus.PROCESSING);
	}

	/**
	 * The notification was sent successfully. Moves from PROCESSING to COMPLETED.
	 * Records the completion timestamp. In queue terms: the consumer acknowledges the message.
	 */
	public void markCompleted() {
		transitionTo(ReminderJobStatus.COMPLETED);
		this.processedAt = Instant.now();
	}

	/**
	 * The notification could not be sent. Moves from PROCESSING to FAILED.
	 * Records the failure timestamp. Stage 7 adds retry logic for failed jobs.
	 * In queue terms: the consumer rejects (nacks) the message.
	 */
	public void markFailed() {
		transitionTo(ReminderJobStatus.FAILED);
		this.processedAt = Instant.now();
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

	private void transitionTo(ReminderJobStatus next) {
		if (!status.canTransitionTo(next)) {
			throw new IllegalStateException(
					"Cannot move job from %s to %s".formatted(status, next));
		}
		this.status = next;
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
		return "ReminderJob{id=%s, reminderId=%s, status=%s}".formatted(id, getReminderId(), status);
	}

}
