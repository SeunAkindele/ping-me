package com.pingme.notification;

/**
 * Stage 6/7 — Signals that a notification delivery failed.
 *
 * <p>Wrapping the root cause (the {@code cause} parameter) preserves the original error for
 * logging while giving the worker a small, predictable set of exception types to catch.
 * Without this, the worker would need to catch {@code IOException}, {@code TimeoutException},
 * {@code SmtpException}, etc. — one per provider. Domain exceptions wrap infrastructure
 * exceptions so business logic handles a few types, not a dozen.
 *
 * <h3>Stage 7 — failures come in two kinds</h3>
 *
 * <p>The single most important retry decision is: <em>will trying again help?</em> Only the
 * adapter that talks to the provider can answer that, because only it understands the provider's
 * error codes. So the adapter expresses its answer through the exception type:
 *
 * <ul>
 *   <li>{@link TransientNotificationException} — probably temporary (timeout, connection reset,
 *       HTTP 429/503). The worker retries with backoff.</li>
 *   <li>{@link PermanentNotificationException} — retrying cannot succeed (invalid address,
 *       recipient unsubscribed, HTTP 400). The worker dead-letters the job immediately.</li>
 * </ul>
 *
 * <p>The class is {@code sealed}: these two subtypes are the only ones allowed. Every
 * {@code NotificationException} the worker sees is therefore exactly one of the two, and adding a
 * third kind later is a compile-time decision rather than a surprise at runtime. It is
 * {@code abstract} so no one can throw an unclassified failure.
 */
public abstract sealed class NotificationException extends RuntimeException
		permits TransientNotificationException, PermanentNotificationException {

	protected NotificationException(String message) {
		super(message);
	}

	protected NotificationException(String message, Throwable cause) {
		super(message, cause);
	}

}
