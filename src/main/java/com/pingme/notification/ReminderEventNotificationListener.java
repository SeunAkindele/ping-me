package com.pingme.notification;

import com.pingme.common.AsyncEventConfig;
import com.pingme.reminder.event.ReminderCreatedEvent;
import com.pingme.reminder.event.ReminderSentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Stage 8 — The notification module's reaction to reminder events (the <b>subscriber</b>).
 *
 * <pre>
 *   ReminderService ──publish──▶ ReminderCreatedEvent ──▶ this.onReminderCreated   (confirmation)
 *                                                    └──▶ ReminderAuditListener    (audit trail)
 *
 *   ReminderJobService ─publish─▶ ReminderSentEvent ────▶ this.onReminderSent      (delivery receipt)
 *                                                    └──▶ ReminderAuditListener    (audit trail)
 * </pre>
 *
 * <p>One event, many independent subscribers (fan-out). The publishers never reference this
 * class; Spring finds it by scanning for listener annotations and routes events by their Java
 * type. Delete this class and reminders are still created and delivered: that is loose coupling.
 *
 * <h3>Why {@code @TransactionalEventListener(phase = AFTER_COMMIT)}</h3>
 *
 * <p>A plain {@code @EventListener} would run the moment {@code publishEvent} is called, which is
 * still <em>inside</em> the publisher's transaction. If that transaction later rolled back, we
 * would already have told the user "your reminder is scheduled" for a reminder that does not
 * exist. {@code AFTER_COMMIT} makes Spring hold the event until the commit succeeds and silently
 * drop it on rollback. Caveat: if an event is published with no transaction active, an
 * {@code AFTER_COMMIT} listener is skipped entirely (unless {@code fallbackExecution = true}).
 * Both of our publishers are {@code @Transactional}, so this does not apply.
 *
 * <h3>Why {@code @Async}</h3>
 *
 * <p>These reactions talk to the outside world, which is slow. Running them on the
 * {@link AsyncEventConfig#EVENT_EXECUTOR} pool means {@code POST /reminders} returns as soon as
 * the reminder is committed, and the worker can move on to its next job without waiting for a
 * receipt to be written.
 *
 * <h3>Failure handling</h3>
 *
 * <p>Each handler catches its own exceptions. A failed confirmation must never affect the
 * reminder (it is already committed) and, being a nice-to-have, is logged rather than retried.
 * If confirmations ever become mandatory, they should move to the durable job queue instead.
 */
@Component
public class ReminderEventNotificationListener {

	private static final Logger log = LoggerFactory.getLogger(ReminderEventNotificationListener.class);

	/**
	 * Sends the user a "your reminder is scheduled" confirmation.
	 *
	 * <p>For learning, the confirmation is printed to the console, like
	 * {@link ConsoleNotificationSender}. Note that it does not go through
	 * {@link NotificationSender}: that port is the contract for delivering the reminder itself
	 * (with retry and failure classification), and a confirmation is a different message type.
	 */
	@Async(AsyncEventConfig.EVENT_EXECUTOR)
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onReminderCreated(ReminderCreatedEvent event) {
		try {
			log.info("""
					\n\
					───────────────────────────────────────────
					  REMINDER SCHEDULED (confirmation)
					  To:        {} ({})
					  Reminder:  #{} "{}"
					  Due at:    {}
					───────────────────────────────────────────""",
					event.recipientEmail(),
					event.recipientName(),
					event.reminderId(),
					event.title(),
					event.scheduledTime());
		}
		catch (RuntimeException ex) {
			log.warn("Could not send confirmation for reminder {}: {}", event.reminderId(), ex.getMessage(), ex);
		}
	}

	/**
	 * Records a delivery receipt for a reminder that was sent.
	 *
	 * <p>In a real system this might update a "last notified" timestamp, increment a usage counter
	 * for billing, or push a websocket update to the user's open app. Because delivery is
	 * at-least-once, this handler may see the same {@code reminderId} twice; anything stateful
	 * here should de-duplicate on {@link ReminderSentEvent#jobId()} / {@code reminderId}.
	 */
	@Async(AsyncEventConfig.EVENT_EXECUTOR)
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onReminderSent(ReminderSentEvent event) {
		try {
			log.info("Delivery receipt: reminder {} (job {}) delivered to {} on attempt {} at {}",
					event.reminderId(),
					event.jobId(),
					event.recipientEmail(),
					event.attemptCount(),
					event.occurredAt());
		}
		catch (RuntimeException ex) {
			log.warn("Could not record delivery receipt for reminder {}: {}", event.reminderId(), ex.getMessage(), ex);
		}
	}

}
