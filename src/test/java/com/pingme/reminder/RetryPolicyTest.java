package com.pingme.reminder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Stage 7 — Plain unit test: no Spring context, no database, runs in milliseconds.
 *
 * <p>This is the payoff of keeping retry rules in a small pure object. Every edge of the backoff
 * schedule can be pinned down here without starting PostgreSQL or waiting for real time to pass.
 */
class RetryPolicyTest {

	private final RetryPolicy policy =
			new RetryPolicy(3, Duration.ofSeconds(30), 2.0, Duration.ofMinutes(10));

	@Test
	void allowsRetriesUntilMaxAttemptsIsReached() {
		assertTrue(policy.canRetry(1));
		assertTrue(policy.canRetry(2));
		assertFalse(policy.canRetry(3), "third failed attempt is the last one");
	}

	@Test
	void backoffGrowsExponentially() {
		assertEquals(Duration.ofSeconds(30), policy.backoffAfter(1));
		assertEquals(Duration.ofSeconds(60), policy.backoffAfter(2));
		assertEquals(Duration.ofSeconds(120), policy.backoffAfter(3));
	}

	@Test
	void backoffIsCappedAtMaxBackoffEvenForHugeAttemptCounts() {
		assertEquals(Duration.ofMinutes(10), policy.backoffAfter(6));
		// 2^999 overflows a long; the cap must be applied before conversion.
		assertEquals(Duration.ofMinutes(10), policy.backoffAfter(1000));
	}

	@Test
	void multiplierOfOneGivesFixedDelay() {
		RetryPolicy fixed = new RetryPolicy(5, Duration.ofSeconds(15), 1.0, Duration.ofMinutes(1));
		assertEquals(Duration.ofSeconds(15), fixed.backoffAfter(1));
		assertEquals(Duration.ofSeconds(15), fixed.backoffAfter(4));
	}

	@Test
	void rejectsInvalidConfiguration() {
		assertThrows(IllegalArgumentException.class,
				() -> new RetryPolicy(0, Duration.ofSeconds(30), 2.0, Duration.ofMinutes(10)));
		assertThrows(IllegalArgumentException.class,
				() -> new RetryPolicy(3, Duration.ofSeconds(-1), 2.0, Duration.ofMinutes(10)));
		assertThrows(IllegalArgumentException.class,
				() -> new RetryPolicy(3, Duration.ofSeconds(30), 0.5, Duration.ofMinutes(10)));
		assertThrows(IllegalArgumentException.class,
				() -> new RetryPolicy(3, Duration.ofMinutes(20), 2.0, Duration.ofMinutes(10)));
	}

	@Test
	void rejectsBackoffRequestBeforeAnyAttempt() {
		assertThrows(IllegalArgumentException.class, () -> policy.backoffAfter(0));
	}

}
