package com.pingme.reminder;

/**
 * Stage 6/7 — Job lifecycle states for the database-backed queue.
 *
 * <p>A {@link ReminderJob} moves through these states as it travels from producer to consumer.
 * Stage 7 adds one edge, {@code PROCESSING → QUEUED}, which is a retry:
 *
 * <pre>
 *                  ┌──────── retry (transient failure, attempts left) ────────┐
 *                  ▼                                                          │
 *   (scheduler) QUEUED ──claim──→ PROCESSING ──success──→ COMPLETED           │
 *                                     │  │                                    │
 *                                     │  └────────────────────────────────────┘
 *                                     │
 *                                     └──permanent failure / attempts exhausted──→ FAILED (dead letter)
 * </pre>
 *
 * <ul>
 *   <li>{@code QUEUED} — waiting in the queue. Either a new job, or a failed job waiting out its
 *       backoff. The job's {@code nextAttemptAt} says when a worker may take it.</li>
 *   <li>{@code PROCESSING} — a worker has claimed this job and is sending the notification.</li>
 *   <li>{@code COMPLETED} — the notification was delivered. Terminal.</li>
 *   <li>{@code FAILED} — the job will never be retried automatically: either the error was
 *       permanent or every allowed attempt failed. Terminal. In queue vocabulary these rows form
 *       the <b>dead letter queue</b> (DLQ): parked messages kept for a human to inspect, fix the
 *       root cause, and replay if appropriate.</li>
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
	 * Guards valid transitions. Terminal states are final.
	 *
	 * <p>In queue-broker terms:
	 * <ul>
	 *   <li>QUEUED → PROCESSING = consumer receives the message</li>
	 *   <li>PROCESSING → COMPLETED = consumer acknowledges (acks) the message</li>
	 *   <li>PROCESSING → QUEUED = consumer negatively acknowledges (nacks) with requeue — Stage 7</li>
	 *   <li>PROCESSING → FAILED = message is routed to the dead letter queue</li>
	 * </ul>
	 *
	 * <p>Note what is <em>not</em> allowed: FAILED → QUEUED. Automatic retries never resurrect a
	 * dead-lettered job. Replaying from the DLQ is a deliberate operator action, and would be
	 * modelled as its own explicit method if we added it.
	 */
	public boolean canTransitionTo(ReminderJobStatus next) {
		return switch (this) {
			case QUEUED -> next == PROCESSING;
			case PROCESSING -> next == COMPLETED || next == QUEUED || next == FAILED;
			case COMPLETED, FAILED -> false;
		};
	}

}
