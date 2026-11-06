package com.pingme.reminder;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Stage 6 — Background worker that drains the job queue and sends notifications.
 *
 * <h3>Why two background loops? (Scheduler vs. Worker)</h3>
 *
 * <p>The {@link ReminderScheduler} answers: <em>"which reminders are due right now?"</em>
 * This worker answers: <em>"which jobs are ready to be executed?"</em>
 *
 * <p>They are separate components because they do fundamentally different work:
 *
 * <pre>
 *   ┌──────────────┬────────────────────────┬─────────────────────────────┐
 *   │              │ Scheduler              │ Worker                      │
 *   ├──────────────┼────────────────────────┼─────────────────────────────┤
 *   │ Reads        │ reminders table         │ reminder_jobs table         │
 *   │ Speed        │ Fast (index scan)       │ Slow (sends notifications)  │
 *   │ Failure risk │ Low (retries next tick) │ High (must not double-send) │
 *   │ Scaling      │ One instance is enough  │ Can run multiple workers    │
 *   └──────────────┴────────────────────────┴─────────────────────────────┘
 * </pre>
 *
 * <p>In production, the scheduler might be a single cron process and the workers might be
 * a pool of containers pulling from RabbitMQ or SQS. Here both run in the same JVM on the
 * Spring task scheduler, but the architectural boundary is already in place.
 *
 * <h3>How one tick of the worker loop works</h3>
 *
 * <ol>
 *   <li>{@code claimQueuedJobs(batchSize)} — <b>one transaction:</b> lock QUEUED jobs,
 *       mark them PROCESSING, commit, release locks. Returns claimed job IDs.</li>
 *   <li>For each claimed job ID, call {@code processJob(id)} — <b>one transaction per job:</b>
 *       load the job, send the notification, update status, commit.</li>
 *   <li>Wait {@code pingme.worker.interval} and repeat.</li>
 * </ol>
 *
 * <p>{@code fixedDelay} (not {@code fixedRate}) ensures the next tick starts only after the
 * previous tick finishes, including all job processing. This prevents overlapping ticks within
 * this JVM. {@code FOR UPDATE SKIP LOCKED} prevents overlapping across JVMs.
 *
 * <h3>Error isolation</h3>
 *
 * <p>The for-loop catches unexpected exceptions per job so one poisoned job does not abort
 * the entire batch. If job #2 throws an unexpected error, jobs #3 through #50 still get
 * processed. The failed job stays in PROCESSING; Stage 7's recovery handles it.
 */
@Component
@ConditionalOnProperty(prefix = "pingme.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReminderJobWorker {

	private static final Logger log = LoggerFactory.getLogger(ReminderJobWorker.class);

	private final ReminderJobService reminderJobService;
	private final WorkerProperties properties;

	public ReminderJobWorker(ReminderJobService reminderJobService, WorkerProperties properties) {
		this.reminderJobService = reminderJobService;
		this.properties = properties;
		log.info(
				"Notification worker enabled: polling every {} after previous tick, batch size {}",
				properties.interval(),
				properties.batchSize());
	}

	/**
	 * Single tick of the worker loop. Thin on purpose — all business logic lives in
	 * {@link ReminderJobService}. This method is just the trigger.
	 */
	@Scheduled(fixedDelayString = "${pingme.worker.interval}")
	public void processQueuedJobs() {
		List<Long> claimedJobIds = reminderJobService.claimQueuedJobs(properties.batchSize());

		for (Long jobId : claimedJobIds) {
			try {
				reminderJobService.processJob(jobId);
			}
			catch (Exception ex) {
				// Catch-all so one broken job does not abort the rest of the batch.
				// The job is already in PROCESSING; Stage 7's recovery will reclaim it.
				log.error("Unexpected error processing job {}: {}", jobId, ex.getMessage(), ex);
			}
		}
	}

}
