package com.pingme.notification;

/**
 * Stage 7 — A delivery failure that is likely to succeed if retried later.
 *
 * <p>Typical causes: network timeout, connection refused, DNS hiccup, provider rate limit
 * (HTTP 429), provider temporarily unavailable (HTTP 502/503/504).
 *
 * <p>The worker answers this exception by putting the job back in the queue with an
 * exponentially growing delay, until {@code pingme.retry.max-attempts} is reached. After that
 * even a transient failure is treated as final and the job goes to the dead letter queue.
 *
 * <p>Rule of thumb for adapter authors: when unsure, throw this one. A wrongly-retried permanent
 * error costs a few extra attempts; a wrongly-abandoned transient error loses a notification.
 */
public final class TransientNotificationException extends NotificationException {

	public TransientNotificationException(String message) {
		super(message);
	}

	public TransientNotificationException(String message, Throwable cause) {
		super(message, cause);
	}

}
