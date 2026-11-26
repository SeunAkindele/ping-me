package com.pingme.common;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Stage 8 — Thread pool for asynchronous event listeners.
 *
 * <h3>Synchronous vs. asynchronous listeners</h3>
 *
 * <p>By default a Spring event listener runs <b>synchronously, on the publisher's thread</b>.
 * {@code publishEvent(...)} does not return until every listener has finished. For an HTTP request
 * that means a slow listener (say, an SMTP call taking two seconds) makes the API response two
 * seconds slower, and a listener that throws can surface as an error to the caller.
 *
 * <p>Annotating a listener with {@code @Async(EVENT_EXECUTOR)} hands it to this pool instead.
 * The publisher returns immediately; the listener runs in the background. The trade-offs:
 *
 * <ul>
 *   <li>The listener runs on a different thread, outside the publisher's transaction. It must
 *       not rely on lazy-loaded entities (one reason events carry plain data).</li>
 *   <li>Exceptions do not propagate to the publisher. They are logged by Spring's
 *       {@code AsyncUncaughtExceptionHandler}.</li>
 *   <li>Events still queued in memory are lost if the process is killed.</li>
 * </ul>
 *
 * <h3>Why a dedicated, bounded pool</h3>
 *
 * <p>An unbounded queue hides problems: if listeners are slower than publishers, memory fills up
 * silently until the JVM crashes. This pool has a fixed queue capacity. When it is full, the
 * {@link ThreadPoolExecutor.CallerRunsPolicy} runs the listener on the publishing thread. That
 * slows the publisher down (back-pressure) instead of dropping events or exhausting memory.
 *
 * <p>It is also separate from the scheduling pool used by the scheduler and worker
 * ({@code spring.task.scheduling.*}), so a burst of events can never delay a scheduler tick.
 *
 * <p>{@code @EnableAsync} switches on the proxy that makes {@code @Async} work. Without it the
 * annotation is silently ignored and listeners run synchronously.
 */
@Configuration
@EnableAsync
public class AsyncEventConfig {

	/** Bean name referenced by {@code @Async(AsyncEventConfig.EVENT_EXECUTOR)}. */
	public static final String EVENT_EXECUTOR = "eventExecutor";

	@Bean(name = EVENT_EXECUTOR)
	public ThreadPoolTaskExecutor eventExecutor(
			@Value("${pingme.events.executor.core-size:2}") int coreSize,
			@Value("${pingme.events.executor.max-size:4}") int maxSize,
			@Value("${pingme.events.executor.queue-capacity:500}") int queueCapacity) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(coreSize);
		executor.setMaxPoolSize(maxSize);
		executor.setQueueCapacity(queueCapacity);
		// Thread names show up in logs: "pingme-event-1" makes async listener output easy to spot.
		executor.setThreadNamePrefix("pingme-event-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
		// Graceful shutdown: give in-flight listeners a few seconds to finish on redeploy rather
		// than killing them halfway.
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(10);
		return executor;
	}

}
