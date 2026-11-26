package com.pingme.reminder.event;

import java.time.Instant;
import java.util.Objects;

/**
 * Stage 8 — Domain event: "a reminder notification was delivered and the result was committed".
 *
 * <p>Published by {@link com.pingme.reminder.ReminderJobService} when a job reaches COMPLETED and
 * its reminder reaches SENT. It is <b>not</b> published for failed attempts, scheduled retries or
 * dead-lettered jobs: those are different facts and would deserve their own events (for example a
 * {@code ReminderFailedEvent}) if something needed to react to them.
 *
 * <h3>Events are for side effects, not for the main workflow</h3>
 *
 * <p>Notice what this event does <em>not</em> do: it does not drive delivery. Delivery is driven by
 * the {@code reminder_jobs} table (Stage 6/7), which survives crashes and supports retries.
 * Spring application events live only in memory: if the JVM dies between the commit and the
 * listener running, the event is gone. So the rule is:
 *
 * <ul>
 *   <li>Work that <b>must</b> happen (sending the reminder) → durable queue table.</li>
 *   <li>Reactions that are nice to have and can tolerate an occasional loss (a delivery receipt,
 *       an audit log line, a metrics counter) → in-memory events.</li>
 * </ul>
 *
 * <p>When a reaction becomes business-critical, the professional upgrade is the <b>transactional
 * outbox</b> pattern: write the event into an {@code outbox} table in the same transaction as the
 * state change, and let a relay publish it. That is the same idea as our job table.
 *
 * @param reminderId     the reminder that was delivered
 * @param jobId          the queue job that delivered it; useful for tracing and as an
 *                       idempotency key, since at-least-once delivery means a consumer may see
 *                       the same reminder twice
 * @param userId         owner of the reminder
 * @param recipientEmail address the notification was sent to
 * @param title          reminder title
 * @param attemptCount   which attempt succeeded (1 = first try, 2+ = after retries)
 * @param occurredAt     when delivery was recorded
 */
public record ReminderSentEvent(
		Long reminderId,
		Long jobId,
		Long userId,
		String recipientEmail,
		String title,
		int attemptCount,
		Instant occurredAt) {

	public ReminderSentEvent {
		Objects.requireNonNull(reminderId, "reminderId is required");
		Objects.requireNonNull(jobId, "jobId is required");
		Objects.requireNonNull(userId, "userId is required");
		Objects.requireNonNull(occurredAt, "occurredAt is required");
		if (attemptCount < 1) {
			throw new IllegalArgumentException("attemptCount must be at least 1 for a delivered reminder");
		}
	}

}
