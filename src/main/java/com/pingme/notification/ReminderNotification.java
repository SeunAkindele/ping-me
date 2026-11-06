package com.pingme.notification;

import java.time.Instant;

/**
 * Stage 6 — Value object carrying the data a notification sender needs.
 *
 * <p>This record decouples the notification layer from JPA entities. The service maps
 * {@link com.pingme.reminder.Reminder} fields into this record before passing it to the
 * sender. If you later switch from console to email or push, the sender's contract stays
 * the same — it always receives a {@code ReminderNotification}, never a JPA entity.
 *
 * <h3>Why not pass the Reminder entity directly?</h3>
 *
 * <ul>
 *   <li>A JPA entity is tied to a Hibernate session. Passing it across layers can trigger
 *       unexpected lazy loads or detached-entity exceptions.</li>
 *   <li>The sender only needs five fields, not the full entity graph. A value object makes
 *       the dependency explicit: the sender cannot accidentally access reminder.getId() or
 *       reminder.getStatus().</li>
 *   <li>When you replace ConsoleNotificationSender with a real SMTP adapter, the adapter
 *       has a clear contract: these five fields, nothing more.</li>
 * </ul>
 *
 * <p>This is the same idea as DTOs for REST responses — translate internal state into a shape
 * the consumer needs, hiding everything else.
 */
public record ReminderNotification(
		String recipientEmail,
		String recipientName,
		String title,
		String description,
		Instant scheduledTime) {
}
