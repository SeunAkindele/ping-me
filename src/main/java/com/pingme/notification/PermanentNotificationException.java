package com.pingme.notification;

/**
 * Stage 7 — A delivery failure that will fail again no matter how often it is retried.
 *
 * <p>Typical causes: malformed or non-existent recipient address, recipient has unsubscribed or
 * blocked the sender, provider rejects the payload (HTTP 400/422), account not authorised to
 * send to this destination.
 *
 * <p>The worker answers this exception by dead-lettering the job straight away — no retries.
 * Retrying a permanent failure wastes worker capacity, delays other jobs, and with real email or
 * SMS providers can damage your sender reputation or get your account throttled.
 */
public final class PermanentNotificationException extends NotificationException {

	public PermanentNotificationException(String message) {
		super(message);
	}

	public PermanentNotificationException(String message, Throwable cause) {
		super(message, cause);
	}

}
