package com.pingme.reminder;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Stage 6/7 — Locks job rows so a worker claim is exclusive.
 *
 * <p>This is the <b>consumer-side</b> counterpart to {@link ReminderClaimRepository}.
 * The scheduler writes jobs into the queue (producer). The worker reads them out (consumer).
 * Without locking, two worker threads could both read the same QUEUED job and process it twice
 * — sending the user duplicate notifications.
 *
 * <h3>How FOR UPDATE SKIP LOCKED prevents duplicate processing</h3>
 *
 * <p>Imagine two workers (W1, W2) both start polling the queue at the same moment:
 *
 * <pre>
 *   Without locking:
 *     W1: SELECT ... WHERE status = 'QUEUED'  →  sees job #7
 *     W2: SELECT ... WHERE status = 'QUEUED'  →  sees job #7 too!
 *     W1: UPDATE SET status = 'PROCESSING'    →  takes job #7
 *     W2: UPDATE SET status = 'PROCESSING'    →  also takes job #7 → DUPLICATE
 *
 *   With FOR UPDATE SKIP LOCKED:
 *     W1: SELECT ... FOR UPDATE SKIP LOCKED   →  locks job #7, returns it
 *     W2: SELECT ... FOR UPDATE SKIP LOCKED   →  skips job #7 (locked), takes job #8
 *     No duplicates.
 * </pre>
 *
 * <p>This is exactly how production queue consumers work. RabbitMQ calls it "prefetch and ack."
 * SQS calls it "visibility timeout." Here PostgreSQL row locks serve the same purpose.
 *
 * <h3>Stage 7 — two claim queries</h3>
 *
 * <ul>
 *   <li>{@link #lockQueuedJobs(Instant, int)} now also requires {@code next_attempt_at <= now}.
 *       A job waiting out its backoff stays QUEUED but is invisible to workers until its time
 *       comes. That single predicate is how a delayed retry is implemented.</li>
 *   <li>{@link #lockStuckJobs(Instant, int)} finds PROCESSING jobs whose claim is older than the
 *       processing timeout — evidence that the worker which claimed them died.</li>
 * </ul>
 *
 * <p>Both use a stable ORDER BY so the oldest work is handled first and two transactions locking
 * overlapping sets of rows always take the locks in the same order, which prevents deadlocks.
 */
@Repository
public class ReminderJobClaimRepository {

	private final EntityManager entityManager;

	public ReminderJobClaimRepository(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	/**
	 * Locks up to {@code limit} QUEUED jobs that are due for an attempt and returns them in the
	 * order they became eligible.
	 *
	 * <p>Must be called inside an active transaction. The locks are held until that transaction
	 * commits or rolls back — this is what prevents other workers from touching the same rows.
	 *
	 * <p>Two-step query pattern (same as {@link ReminderClaimRepository}):
	 * <ol>
	 *   <li>Native SQL to lock IDs — JPQL cannot express {@code SKIP LOCKED}.</li>
	 *   <li>JPQL to load managed entities — Hibernate needs these for dirty checking.</li>
	 * </ol>
	 *
	 * @param now   current time; jobs with {@code next_attempt_at} after this are still backing off
	 * @param limit maximum number of jobs to lock in this batch
	 * @return locked {@link ReminderJob} entities, earliest-eligible first; never null
	 */
	public List<ReminderJob> lockQueuedJobs(Instant now, int limit) {
		Objects.requireNonNull(now, "now is required");
		requireTransactionAndLimit(limit);

		// Partial index idx_reminder_jobs_queued (V5) covers exactly this predicate + ordering.
		@SuppressWarnings("unchecked")
		List<Number> rawIds = entityManager.createNativeQuery("""
				SELECT id
				FROM reminder_jobs
				WHERE status = 'QUEUED'
				  AND next_attempt_at <= :now
				ORDER BY next_attempt_at, id
				LIMIT :limit
				FOR UPDATE SKIP LOCKED
				""")
				.setParameter("now", now)
				.setParameter("limit", limit)
				.getResultList();

		return loadInOrder(rawIds);
	}

	/**
	 * Stage 7 — Locks up to {@code limit} jobs that have been PROCESSING since before
	 * {@code claimedBefore}.
	 *
	 * <p>A job only stays in PROCESSING that long if the worker that claimed it crashed, was
	 * killed during a deploy, or lost its database connection after the claim committed. Without
	 * this query those jobs would be stranded forever and their reminders never delivered.
	 *
	 * <p>{@code SKIP LOCKED} matters here too: a job whose processing transaction is genuinely
	 * still open holds its row lock, so recovery skips it instead of blocking on it.
	 *
	 * @param claimedBefore cutoff; jobs whose {@code started_at} is earlier are considered stuck
	 * @param limit         maximum number of jobs to lock in this batch
	 * @return locked stuck jobs, oldest claim first; never null
	 */
	public List<ReminderJob> lockStuckJobs(Instant claimedBefore, int limit) {
		Objects.requireNonNull(claimedBefore, "claimedBefore is required");
		requireTransactionAndLimit(limit);

		// Partial index idx_reminder_jobs_processing (V5) covers this scan.
		@SuppressWarnings("unchecked")
		List<Number> rawIds = entityManager.createNativeQuery("""
				SELECT id
				FROM reminder_jobs
				WHERE status = 'PROCESSING'
				  AND started_at < :claimedBefore
				ORDER BY started_at, id
				LIMIT :limit
				FOR UPDATE SKIP LOCKED
				""")
				.setParameter("claimedBefore", claimedBefore)
				.setParameter("limit", limit)
				.getResultList();

		return loadInOrder(rawIds);
	}

	private static void requireTransactionAndLimit(int limit) {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("Job claim queries must run inside a transaction");
		}
		if (limit < 1) {
			throw new IllegalArgumentException("limit must be at least 1");
		}
	}

	/**
	 * Step 2 of the two-step pattern: loads managed entities for the locked IDs and restores the
	 * native query's ordering (an {@code IN} query returns rows in no guaranteed order).
	 * No JOIN FETCH — claiming only changes fields on the job row itself.
	 */
	private List<ReminderJob> loadInOrder(List<Number> rawIds) {
		if (rawIds.isEmpty()) {
			return List.of();
		}

		List<Long> ids = rawIds.stream().map(Number::longValue).toList();
		List<ReminderJob> jobs = entityManager.createQuery("""
				SELECT j
				FROM ReminderJob j
				WHERE j.id IN :ids
				""", ReminderJob.class)
				.setParameter("ids", ids)
				.getResultList();

		Map<Long, ReminderJob> byId = jobs.stream()
				.collect(Collectors.toMap(ReminderJob::getId, Function.identity()));
		return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
	}

}
