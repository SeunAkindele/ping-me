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
 * Stage 5 — Locks due reminder rows so a claim can be exclusive.
 *
 * <p>Two schedulers (or two ticks) that both {@code SELECT} pending rows and then {@code UPDATE}
 * them will each believe they own the same reminder. That is the duplicate-processing bug.
 * {@code FOR UPDATE SKIP LOCKED} closes it: the first transaction locks the rows it will claim;
 * the second skips those rows and takes the next free ones instead of waiting or double-claiming.
 * Locks are held until this transaction commits, so the caller must already be inside one.
 *
 * <p>Rows are locked in {@code (scheduled_time, id)} order. A stable order is what stops two
 * transactions that do block on each other from deadlocking by locking the same rows in opposite
 * orders. The status predicate matches {@link Reminder#isDue(Instant)}: pending and due.
 */
@Repository
public class ReminderClaimRepository {

	private final EntityManager entityManager;

	public ReminderClaimRepository(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	public List<Reminder> lockDueReminders(Instant now, int limit) {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("lockDueReminders must run inside a transaction");
		}
		Objects.requireNonNull(now, "now is required");
		if (limit < 1) {
			throw new IllegalArgumentException("limit must be at least 1");
		}

		@SuppressWarnings("unchecked")
		List<Number> rawIds = entityManager.createNativeQuery("""
				SELECT id
				FROM reminders
				WHERE status = 'PENDING'
				  AND scheduled_time <= :now
				ORDER BY scheduled_time, id
				LIMIT :limit
				FOR UPDATE SKIP LOCKED
				""")
				.setParameter("now", now)
				.setParameter("limit", limit)
				.getResultList();

		if (rawIds.isEmpty()) {
			return List.of();
		}

		List<Long> ids = rawIds.stream().map(Number::longValue).toList();
		List<Reminder> reminders = entityManager.createQuery("""
				SELECT r
				FROM Reminder r
				WHERE r.id IN :ids
				""", Reminder.class)
				.setParameter("ids", ids)
				.getResultList();

		Map<Long, Reminder> byId = reminders.stream()
				.collect(Collectors.toMap(Reminder::getId, Function.identity()));
		return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
	}

}
