package com.pingme.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stage 6 — Adapter that "sends" notifications by logging them to the console.
 *
 * <p>In a real system this class would call an SMTP server (SendGrid, AWS SES), a push
 * notification gateway (Firebase), or an SMS provider (Twilio). For learning, we print to
 * the console so you can see the full flow — from scheduler claim to notification delivery —
 * without configuring external services.
 *
 * <h3>The Adapter pattern</h3>
 *
 * <p>The application declares a port ({@link NotificationSender}). This class plugs into it.
 * Swapping to real email means writing a new adapter, not changing the worker or service:
 *
 * <pre>
 *   // Console (learning)
 *   {@code @Component}
 *   public class ConsoleNotificationSender implements NotificationSender { ... }
 *
 *   // Real email (production) — just add this class and mark it @Primary
 *   {@code @Component @Primary}
 *   public class SmtpNotificationSender implements NotificationSender { ... }
 * </pre>
 *
 * <p>The {@code @Component} annotation registers this class as a Spring bean. Since it is
 * the only implementation of {@link NotificationSender}, Spring auto-wires it wherever
 * that interface is injected — no XML, no manual wiring.
 *
 * <p>The log output is intentionally formatted like an email so you can visually confirm
 * the full pipeline worked when you test it end-to-end.
 */
@Component
public class ConsoleNotificationSender implements NotificationSender {

	private static final Logger log = LoggerFactory.getLogger(ConsoleNotificationSender.class);

	@Override
	public void send(ReminderNotification notification) {
		log.info("""
				\n\
				═══════════════════════════════════════════
				  REMINDER NOTIFICATION
				  To:        {} ({})
				  Subject:   Reminder: {}
				  Body:      {}
				  Scheduled: {}
				═══════════════════════════════════════════""",
				notification.recipientEmail(),
				notification.recipientName(),
				notification.title(),
				notification.description() != null ? notification.description() : "(no description)",
				notification.scheduledTime());
	}

}
