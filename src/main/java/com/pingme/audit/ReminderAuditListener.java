package com.pingme.audit;

import com.pingme.reminder.event.ReminderCreatedEvent;
import com.pingme.reminder.event.ReminderSentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Stage 8 — A second, independent subscriber to the same reminder events.
 *
 * <p>This class exists to make two lessons visible:
 *
 * <ol>
 *   <li><b>Fan-out.</b> {@link ReminderCreatedEvent} now has two subscribers (this one and
 *       {@code ReminderEventNotificationListener}). Neither knows about the other, and the
 *       publisher knows about neither. Adding this audit trail required zero changes to
 *       {@code ReminderService} or {@code ReminderJobService}.</li>
 *   <li><b>Synchronous vs. asynchronous.</b> Unlike the notification listener, this one has no
 *       {@code @Async}. It runs on the publisher's own thread right after the commit, before
 *       {@code createReminder(...)} returns. Compare thread names in the log: this prints on
 *       {@code http-nio-*} or {@code pingme-bg-*}, the notification listener on
 *       {@code pingme-event-*}. Synchronous is fine here because a log line is instant; anything
 *       slow belongs on the async pool.</li>
 * </ol>
 *
 * <p>Even synchronous, an {@code AFTER_COMMIT} listener cannot roll back the publisher's work:
 * the commit has already happened. If this method threw, Spring would log the exception, and the
 * caller of a synchronous listener would see it, even though the reminder is saved. That is why
 * audit failures are caught here.
 *
 * <p>In production this audit trail would be its own table (Nudgler lists "audit logs" as a later
 * addition). Writing to the database from an {@code AFTER_COMMIT} listener needs a <em>new</em>
 * transaction ({@code @Transactional(propagation = REQUIRES_NEW)}), because the original one is
 * finished and can no longer accept writes.
 */
@Component
public class ReminderAuditListener {

	private static final Logger log = LoggerFactory.getLogger(ReminderAuditListener.class);

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onReminderCreated(ReminderCreatedEvent event) {
		audit("REMINDER_CREATED", event.reminderId(), event.userId(),
				"scheduledTime=" + event.scheduledTime(), event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onReminderSent(ReminderSentEvent event) {
		audit("REMINDER_SENT", event.reminderId(), event.userId(),
				"jobId=" + event.jobId() + " attempt=" + event.attemptCount(), event.occurredAt());
	}

	/**
	 * One consistent, grep-friendly format for every audit entry: action, subject, actor, details,
	 * time. Structured lines like this are what log aggregators (ELK, Loki, Datadog) index on.
	 */
	private void audit(String action, Long reminderId, Long userId, String details, Object occurredAt) {
		try {
			log.info("AUDIT action={} reminderId={} userId={} {} occurredAt={}",
					action, reminderId, userId, details, occurredAt);
		}
		catch (RuntimeException ex) {
			log.warn("Audit write failed for {} on reminder {}: {}", action, reminderId, ex.getMessage());
		}
	}

}
