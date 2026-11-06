package com.pingme.reminder;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stage 5 — Knobs for the single reminder claim loop.
 *
 * <p>{@code pingme.scheduler.interval} is the pause after a tick finishes ({@code fixedDelay}),
 * not a cron expression and not a per-user timer. {@code pingme.scheduler.batch-size} caps how
 * many rows one tick locks so a backlog cannot pin the whole table in one transaction.
 */
@ConfigurationProperties(prefix = "pingme.scheduler")
public record SchedulerProperties(Duration interval, int batchSize) {

	public SchedulerProperties {
		if (interval == null || interval.isZero() || interval.isNegative()) {
			throw new IllegalArgumentException("pingme.scheduler.interval must be positive");
		}
		if (batchSize < 1) {
			throw new IllegalArgumentException("pingme.scheduler.batch-size must be at least 1");
		}
	}

}
