package com.pingme.reminder;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Stage 6 — Locks queued job rows so a worker claim is exclusive.
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
 * <p>Rows are ordered by {@code (created_at, id)} so older jobs are processed first (FIFO —
 * first in, first out). A stable order also prevents deadlocks when two transactions lock
 * overlapping sets of rows.
 */
@Repository
public class ReminderJobClaimRepository {

	private final EntityManager entityManager;

	public ReminderJobClaimRepository(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	/**
	 * Locks up to {@code limit} QUEUED jobs and returns them in FIFO order.
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
	 * @param limit maximum number of jobs to lock in this batch
	 * @return locked {@link ReminderJob} entities, oldest first; never null
	 */
	public List<ReminderJob> lockQueuedJobs(int limit) {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("lockQueuedJobs must run inside a transaction");
		}
		if (limit < 1) {
			throw new IllegalArgumentException("limit must be at least 1");
		}

		// Step 1: Lock job row IDs with native SQL.
		// The partial index idx_reminder_jobs_queued covers exactly this predicate + ordering.
		@SuppressWarnings("unchecked")
		List<Number> rawIds = entityManager.createNativeQuery("""
				SELECT id
				FROM reminder_jobs
				WHERE status = 'QUEUED'
				ORDER BY created_at, id
				LIMIT :limit
				FOR UPDATE SKIP LOCKED
				""")
				.setParameter("limit", limit)
				.getResultList();

		if (rawIds.isEmpty()) {
			return List.of();
		}

		// Step 2: Load full entities via JPQL so Hibernate can track changes.
		// No JOIN FETCH here — the worker does not need reminder/user data during the claim.
		// It only needs to change the job's status field.
		List<Long> ids = rawIds.stream().map(Number::longValue).toList();
		List<ReminderJob> jobs = entityManager.createQuery("""
				SELECT j
				FROM ReminderJob j
				WHERE j.id IN :ids
				""", ReminderJob.class)
				.setParameter("ids", ids)
				.getResultList();

		// Re-order to match the native query's FIFO ordering.
		Map<Long, ReminderJob> byId = jobs.stream()
				.collect(Collectors.toMap(ReminderJob::getId, Function.identity()));
		return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
	}

}
