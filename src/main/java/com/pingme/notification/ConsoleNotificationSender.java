package com.pingme.notification;

import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stage 6/7 — Adapter that "sends" notifications by logging them to the console.
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
 * <h3>Stage 7 — simulated failures (learning aid only)</h3>
 *
 * <p>A console never fails, so on its own it cannot show the retry system working. When
 * {@code pingme.notification.console.simulate-failures=true}, a marker in the reminder title
 * makes this adapter fail on purpose:
 *
 * <pre>
 *   Title contains        Behaviour                         What you should observe
 *   ───────────────────   ───────────────────────────────   ───────────────────────────────────────
 *   [fail-transient]      every attempt fails, transiently  3 attempts with growing delays, then
 *                                                           job FAILED and reminder FAILED
 *   [fail-permanent]      fails permanently                 1 attempt, then straight to FAILED
 *   [flaky]               fails transiently ~50% of tries   usually SENT after one or two retries
 * </pre>
 *
 * <p>A real adapter would map provider responses instead, for example HTTP 503 or a socket
 * timeout to {@link TransientNotificationException} and HTTP 400 "invalid recipient" to
 * {@link PermanentNotificationException}. The simulation is off by default so a fresh checkout
 * behaves exactly like Stage 6.
 */
@Component
public class ConsoleNotificationSender implements NotificationSender {

	private static final Logger log = LoggerFactory.getLogger(ConsoleNotificationSender.class);

	static final String FAIL_TRANSIENT_MARKER = "[fail-transient]";
	static final String FAIL_PERMANENT_MARKER = "[fail-permanent]";
	static final String FLAKY_MARKER = "[flaky]";

	private final boolean simulateFailures;

	public ConsoleNotificationSender(
			@Value("${pingme.notification.console.simulate-failures:false}") boolean simulateFailures) {
		this.simulateFailures = simulateFailures;
		if (simulateFailures) {
			log.warn("Console notification sender is SIMULATING FAILURES for titles containing {}, {} or {}",
					FAIL_TRANSIENT_MARKER, FAIL_PERMANENT_MARKER, FLAKY_MARKER);
		}
	}

	@Override
	public void send(ReminderNotification notification) {
		if (simulateFailures) {
			simulateFailureIfRequested(notification.title());
		}

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

	/**
	 * Throws the exception a real provider integration would throw for the marker in the title.
	 * Permanent is checked first so a title with both markers behaves like the stricter case.
	 */
	private static void simulateFailureIfRequested(String title) {
		if (title.contains(FAIL_PERMANENT_MARKER)) {
			throw new PermanentNotificationException("Simulated permanent failure: recipient address rejected");
		}
		if (title.contains(FAIL_TRANSIENT_MARKER)) {
			throw new TransientNotificationException("Simulated transient failure: provider timed out");
		}
		if (title.contains(FLAKY_MARKER) && ThreadLocalRandom.current().nextBoolean()) {
			throw new TransientNotificationException("Simulated flaky failure: provider returned 503");
		}
	}

}
