package com.pingme.notification;

/**
 * Stage 6 — Port for sending reminder notifications.
 *
 * <p>An interface (port) defines <em>what</em> the application needs without saying <em>how</em>.
 * The application service calls {@link #send(ReminderNotification)}. The implementation decides
 * the delivery channel: console log now, SMTP later, push notifications eventually.
 *
 * <h3>Dependency Inversion Principle in action</h3>
 *
 * <p>Without this interface, the worker would depend on {@code ConsoleNotificationSender} directly.
 * To switch to real email, you would have to change the worker code. With this interface:
 *
 * <ul>
 *   <li>High-level module ({@code ReminderJobService}) depends on this interface.</li>
 *   <li>Low-level module ({@code ConsoleNotificationSender}) implements this interface.</li>
 *   <li>Neither depends on the other's concrete class.</li>
 * </ul>
 *
 * <p>To switch from console to real email, you write a new implementation (e.g.,
 * {@code SmtpNotificationSender}), mark it {@code @Primary} or use a Spring profile,
 * and the worker code does not change at all.
 *
 * <h3>Why this matters for senior engineering</h3>
 *
 * <p>Every external integration in a backend should sit behind an interface:
 * email providers, SMS gateways, payment processors, push notification services.
 * This lets you swap providers, add fallbacks, and write tests with fakes — all without
 * touching your business logic.
 */
public interface NotificationSender {

	/**
	 * Sends a reminder notification to the recipient.
	 *
	 * @param notification the data needed to build and deliver the notification
	 * @throws NotificationException if the delivery failed (network error, provider rejected, etc.)
	 */
	void send(ReminderNotification notification);

}
