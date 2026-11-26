package com.pingme.reminder;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Stage 6/7 — Background worker that drains the job queue and sends notifications.
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
 *   <li>Stage 7: {@code recoverStuckJobs(...)} — <b>one transaction:</b> find jobs left in
 *       PROCESSING longer than {@code pingme.worker.processing-timeout} (their worker died) and
 *       put them back in the queue or the dead letter queue. Done first so recovered jobs that
 *       are immediately eligible can be claimed in the same tick.</li>
 *   <li>{@code claimQueuedJobs(now, batchSize)} — <b>one transaction:</b> lock QUEUED jobs whose
 *       {@code nextAttemptAt} has arrived, mark them PROCESSING, commit. Returns claimed IDs.</li>
 *   <li>For each claimed job ID, call {@code processJob(id, now)} — <b>one transaction per job:</b>
 *       load the job, send the notification, then record success, a scheduled retry, or a
 *       dead-letter.</li>
 *   <li>Wait {@code pingme.worker.interval} and repeat.</li>
 * </ol>
 *
 * <h3>Stage 7 — where retries actually happen</h3>
 *
 * <p>There is no separate "retry thread" and no in-memory {@code Thread.sleep(backoff)}. A failed
 * job is written back to the table as QUEUED with a future {@code nextAttemptAt}, and this same
 * loop picks it up on the first tick after that time. Keeping retry state in the database means
 * a restart loses nothing, and any worker instance can perform the retry.
 *
 * <p>Consequence: the effective delay is the backoff <em>rounded up to the next tick</em>. With a
 * 10s interval, a 30s backoff becomes 30–40s. Fine for reminders; tighten the interval if you
 * need more precise retry timing.
 *
 * <p>{@code Instant.now()} is read here, at the edge, and passed down. Services and entities
 * receive time as a parameter, which keeps them deterministic and easy to test.
 *
 * <p>{@code fixedDelay} (not {@code fixedRate}) ensures the next tick starts only after the
 * previous tick finishes, including all job processing. This prevents overlapping ticks within
 * this JVM. {@code FOR UPDATE SKIP LOCKED} prevents overlapping across JVMs.
 *
 * <h3>Error isolation</h3>
 *
 * <p>The for-loop catches unexpected exceptions per job so one poisoned job does not abort
 * the entire batch. If job #2 throws an unexpected error, jobs #3 through #50 still get
 * processed.
 *
 * <p>Send failures never reach that catch: {@link ReminderJobService#processJob} turns them into
 * retry or dead-letter state itself. Only infrastructure errors do (for example the database
 * going away mid-transaction). Then the transaction rolls back and the job stays PROCESSING;
 * stuck-job recovery picks it up once the processing timeout passes.
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
				"Notification worker enabled: polling every {} after previous tick, batch size {}, processing timeout {}",
				properties.interval(),
				properties.batchSize(),
				properties.processingTimeout());
	}

	/**
	 * Single tick of the worker loop. Thin on purpose — all business logic lives in
	 * {@link ReminderJobService}. This method is just the trigger.
	 */
	@Scheduled(fixedDelayString = "${pingme.worker.interval}")
	public void processQueuedJobs() {
		// Stage 7: rescue jobs abandoned by a crashed worker before claiming new work.
		reminderJobService.recoverStuckJobs(Instant.now(), properties.processingTimeout(), properties.batchSize());

		List<Long> claimedJobIds = reminderJobService.claimQueuedJobs(Instant.now(), properties.batchSize());

		for (Long jobId : claimedJobIds) {
			try {
				// Fresh timestamp per job: in a large batch, later jobs start noticeably later,
				// and their backoff should be measured from when *they* failed.
				reminderJobService.processJob(jobId, Instant.now());
			}
			catch (Exception ex) {
				// Catch-all so one broken job does not abort the rest of the batch.
				// The transaction rolled back, so the job is still PROCESSING;
				// recoverStuckJobs will requeue it after the processing timeout.
				log.error("Unexpected error processing job {}: {}", jobId, ex.getMessage(), ex);
			}
		}
	}

}
