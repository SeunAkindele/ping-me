package com.pingme.reminder;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Stage 5 — The only reminder trigger in the process.
 *
 * <p>A background job here is a method Spring calls on a scheduler thread, outside any HTTP
 * request. One component scans the {@code reminders} table. Users are data on the row, not owners
 * of their own timer. A cron per user would multiply timers, hide failures, and still need the
 * same due-query the moment the process restarts.
 *
 * <p>{@code fixedDelay} starts the next tick {@code pingme.scheduler.interval} after this one
 * returns, so this JVM does not run two claims at once. That does not protect a second instance of
 * the application. The row lock in {@link ReminderClaimRepository} does.
 *
 * <p>The scheduled method stays thin on purpose. {@code @Transactional} on this class would not run
 * if the method called {@code this.claim...}: Spring's scheduler enters through the proxy, but a
 * self-call does not. The transaction lives on {@link ReminderClaimService}. An exception rolls
 * that transaction back and Spring logs it; the following tick tries again. Nothing is marked sent
 * here.
 */
@Component
@ConditionalOnProperty(prefix = "pingme.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReminderScheduler {

	private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

	private final ReminderClaimService reminderClaimService;
	private final SchedulerProperties properties;

	public ReminderScheduler(ReminderClaimService reminderClaimService, SchedulerProperties properties) {
		this.reminderClaimService = reminderClaimService;
		this.properties = properties;
		log.info(
				"Reminder scheduler enabled: one claim loop every {} after the previous tick, batch size {}",
				properties.interval(),
				properties.batchSize());
	}

	@Scheduled(fixedDelayString = "${pingme.scheduler.interval}")
	public void claimDueReminders() {
		reminderClaimService.claimDueReminders(Instant.now(), properties.batchSize());
	}

}
