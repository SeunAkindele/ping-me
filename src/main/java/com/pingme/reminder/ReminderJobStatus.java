package com.pingme.reminder;

/**
 * Stage 6 — Job lifecycle states for the database-backed queue.
 *
 * <p>A {@link ReminderJob} moves through these states as it travels from producer to consumer:
 *
 * <pre>
 *   QUEUED ──→ PROCESSING ──→ COMPLETED
 *                   │
 *                   └──→ FAILED
 * </pre>
 *
 * <ul>
 *   <li>{@code QUEUED} — the scheduler created this job; it is waiting for a worker to pick it up.
 *       This is the equivalent of a message sitting in a RabbitMQ queue, unacknowledged.</li>
 *   <li>{@code PROCESSING} — a worker has claimed this job and is sending the notification.
 *       The row is locked; no other worker will touch it.</li>
 *   <li>{@code COMPLETED} — the notification was sent successfully. Terminal state.</li>
 *   <li>{@code FAILED} — the notification could not be sent. Terminal state for now;
 *       Stage 7 adds retry logic that can move a failed job back to QUEUED.</li>
 * </ul>
 *
 * <p>Transition rules are enforced in the domain so illegal state changes are caught in Java
 * before they ever reach the database, just like {@link ReminderStatus}.
 */
public enum ReminderJobStatus {

	QUEUED,
	PROCESSING,
	COMPLETED,
	FAILED;

	/**
	 * Guards valid transitions. Only forward moves are allowed; terminal states are final.
	 *
	 * <p>In a real queue system:
	 * <ul>
	 *   <li>QUEUED → PROCESSING = consumer picks up the message</li>
	 *   <li>PROCESSING → COMPLETED = consumer acknowledges the message</li>
	 *   <li>PROCESSING → FAILED = consumer rejects the message</li>
	 * </ul>
	 */
	public boolean canTransitionTo(ReminderJobStatus next) {
		return switch (this) {
			case QUEUED -> next == PROCESSING;
			case PROCESSING -> next == COMPLETED || next == FAILED;
			case COMPLETED, FAILED -> false;
		};
	}

}
