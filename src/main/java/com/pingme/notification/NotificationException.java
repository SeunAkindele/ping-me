package com.pingme.notification;

/**
 * Stage 6 — Signals that a notification delivery failed.
 *
 * <p>The worker catches this specific exception to decide what to do next:
 * <ul>
 *   <li>Mark the job as FAILED.</li>
 *   <li>Log the error for investigation.</li>
 *   <li>Stage 7: decide whether to retry or give up based on attempt count.</li>
 * </ul>
 *
 * <p>Wrapping the root cause (the {@code cause} parameter) preserves the original error
 * for logging while giving the worker a single, predictable exception type to catch.
 * Without this, the worker would need to catch {@code IOException}, {@code TimeoutException},
 * {@code SmtpException}, etc. — one per email provider.
 *
 * <p>This is a common pattern: domain-specific exceptions wrap infrastructure exceptions
 * so your business logic handles one type, not a dozen.
 */
public final class NotificationException extends RuntimeException {

	public NotificationException(String message) {
		super(message);
	}

	public NotificationException(String message, Throwable cause) {
		super(message, cause);
	}

}
