package com.pingme.reminder;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stage 7 — Decides <em>whether</em> a failed job is retried and <em>when</em>.
 *
 * <p>The retry rules sit in one small, side-effect-free object instead of being spread across
 * the worker. That makes them easy to read, easy to unit test (no database, no Spring context),
 * and easy to change: switching from exponential to linear backoff means editing one method.
 *
 * <h3>Transient vs. permanent failures</h3>
 *
 * <p>This policy only applies to <b>transient</b> failures: problems likely to go away on their
 * own (network timeout, provider rate limit, HTTP 503). <b>Permanent</b> failures (invalid
 * address, provider says "never deliver to this recipient") skip the policy and go straight to
 * the dead letter queue. Retrying those wastes capacity and can get you rate-limited or flagged
 * as a spammer. The classification happens in {@link ReminderJobService}.
 *
 * <h3>Exponential backoff</h3>
 *
 * <p>The wait before retry {@code n} grows geometrically:
 *
 * <pre>
 *   delay(n) = min(initialBackoff × multiplier^(n − 1), maxBackoff)
 *
 *   With initialBackoff = 30s, multiplier = 2, maxBackoff = 10m:
 *     after attempt 1 fails →  30s
 *     after attempt 2 fails →  60s
 *     after attempt 3 fails → 120s   (only if maxAttempts &gt; 3)
 *     ...
 *     after attempt 6 fails → 10m    (capped)
 * </pre>
 *
 * <p>Why not retry immediately? If a provider is down, hammering it every second makes the
 * outage worse (a "retry storm") and burns all your attempts in a few seconds, before the
 * provider has had a chance to recover. Growing delays give the dependency room to breathe.
 *
 * <p>Production systems usually add <b>jitter</b> (a random offset) so that thousands of jobs
 * that failed at the same moment do not all retry at the same moment. It is left out here to
 * keep the schedule deterministic and the tests simple; it would be a one-line change in
 * {@link #backoffAfter(int)}.
 *
 * <h3>Configuration</h3>
 *
 * <pre>
 *   pingme.retry.max-attempts=3
 *   pingme.retry.initial-backoff=30s
 *   pingme.retry.multiplier=2.0
 *   pingme.retry.max-backoff=10m
 * </pre>
 *
 * <p>The record is bound by {@code @ConfigurationPropertiesScan} and registered as a bean, like
 * {@link WorkerProperties}. The annotation is metadata only: tests can still build one with
 * {@code new RetryPolicy(...)} without any Spring machinery.
 *
 * @param maxAttempts    total attempts allowed, including the first one (3 = 1 try + 2 retries)
 * @param initialBackoff delay after the first failed attempt
 * @param multiplier     growth factor applied to the delay after each further failure
 * @param maxBackoff     upper bound on any single delay
 */
@ConfigurationProperties(prefix = "pingme.retry")
public record RetryPolicy(int maxAttempts, Duration initialBackoff, double multiplier, Duration maxBackoff) {

	/**
	 * Validates configuration at startup. A bad value (zero attempts, negative delay) fails the
	 * application on boot with a clear message, rather than producing odd behaviour at 3 a.m.
	 */
	public RetryPolicy {
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("pingme.retry.max-attempts must be at least 1");
		}
		if (initialBackoff == null || initialBackoff.isNegative()) {
			throw new IllegalArgumentException("pingme.retry.initial-backoff must be zero or positive");
		}
		if (multiplier < 1.0) {
			throw new IllegalArgumentException("pingme.retry.multiplier must be at least 1.0");
		}
		if (maxBackoff == null || maxBackoff.compareTo(initialBackoff) < 0) {
			throw new IllegalArgumentException("pingme.retry.max-backoff must be at least initial-backoff");
		}
	}

	/**
	 * Answers "may this job be tried again?" after it has already been attempted
	 * {@code attemptsMade} times.
	 *
	 * @param attemptsMade attempts already consumed, including the one that just failed
	 * @return {@code true} if at least one more attempt is allowed
	 */
	public boolean canRetry(int attemptsMade) {
		return attemptsMade < maxAttempts;
	}

	/**
	 * How long to wait before the next attempt, given that {@code attemptsMade} attempts have
	 * already failed.
	 *
	 * <p>The calculation is done in milliseconds with {@code double} arithmetic and capped
	 * <em>before</em> converting back to a {@link Duration}. Without the cap, a large exponent
	 * would overflow {@code long} and produce a negative (that is, "retry immediately") delay.
	 *
	 * @param attemptsMade attempts already consumed; must be at least 1
	 * @return the delay before the next attempt, never longer than {@link #maxBackoff()}
	 */
	public Duration backoffAfter(int attemptsMade) {
		if (attemptsMade < 1) {
			throw new IllegalArgumentException("attemptsMade must be at least 1");
		}
		double delayMillis = initialBackoff.toMillis() * Math.pow(multiplier, attemptsMade - 1);
		double cappedMillis = Math.min(delayMillis, maxBackoff.toMillis());
		return Duration.ofMillis((long) cappedMillis);
	}

}
