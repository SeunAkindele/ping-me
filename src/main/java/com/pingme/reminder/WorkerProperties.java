package com.pingme.reminder;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stage 6/7 — Configuration knobs for the notification worker.
 *
 * <p>The worker runs on its own schedule, separate from the scheduler. Why separate config?
 *
 * <ul>
 *   <li><b>Different cadence:</b> The scheduler scans for due reminders (every 30s is fine).
 *       The worker drains the queue (every 10s means faster delivery).</li>
 *   <li><b>Different cost:</b> Scanning the reminders table is a fast index lookup.
 *       Sending notifications involves network I/O. They scale differently.</li>
 *   <li><b>Independent tuning:</b> You can crank up the worker batch size or frequency
 *       without touching the scheduler.</li>
 * </ul>
 *
 * <p>Configuration in {@code application.properties}:
 * <pre>
 *   pingme.worker.enabled=true
 *   pingme.worker.interval=10s
 *   pingme.worker.batch-size=50
 *   pingme.worker.processing-timeout=5m
 * </pre>
 *
 * <h3>Stage 7 — processing timeout (the "visibility timeout")</h3>
 *
 * <p>{@code processing-timeout} is how long a job may sit in PROCESSING before the worker assumes
 * the process that claimed it has died. Such a job is put back in the queue (or dead-lettered if
 * it has no attempts left). This is the same idea as SQS's visibility timeout or RabbitMQ
 * redelivering an un-acked message when the consumer's connection drops.
 *
 * <p>Pick a value comfortably larger than the slowest legitimate send. Too short, and a job
 * that is merely slow gets reclaimed and delivered twice. Too long, and a crashed job waits
 * that long before anyone retries it.
 *
 * <p>This record is validated at startup by its compact constructor. If someone deploys
 * with {@code pingme.worker.interval=0s}, the application fails to start with a clear error
 * instead of silently running a zero-delay loop that hammers the database.
 */
@ConfigurationProperties(prefix = "pingme.worker")
public record WorkerProperties(Duration interval, int batchSize, Duration processingTimeout) {

	public WorkerProperties {
		if (interval == null || interval.isZero() || interval.isNegative()) {
			throw new IllegalArgumentException("pingme.worker.interval must be positive");
		}
		if (batchSize < 1) {
			throw new IllegalArgumentException("pingme.worker.batch-size must be at least 1");
		}
		if (processingTimeout == null || processingTimeout.isZero() || processingTimeout.isNegative()) {
			throw new IllegalArgumentException("pingme.worker.processing-timeout must be positive");
		}
	}

}
