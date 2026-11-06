package com.pingme.reminder;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Stage 6 — Persistence port for {@link ReminderJob}.
 *
 * <p>Simple CRUD and lookup operations live here. The claim query (which needs
 * {@code FOR UPDATE SKIP LOCKED}) lives on {@link ReminderJobClaimRepository} because
 * Spring Data's query DSL cannot express pessimistic locking with skip-locked semantics.
 *
 * <h3>Why a custom findByIdWithReminderAndUser query?</h3>
 *
 * <p>When the worker processes a job, it needs three things: the job, the reminder, and the user
 * (for the notification email). With lazy loading, that is three separate SELECT queries:
 *
 * <pre>
 *   SELECT * FROM reminder_jobs WHERE id = ?      -- load the job
 *   SELECT * FROM reminders WHERE id = ?           -- lazy: job.getReminder()
 *   SELECT * FROM users WHERE id = ?               -- lazy: reminder.getUser()
 * </pre>
 *
 * <p>{@code JOIN FETCH} collapses this into a single query that returns all three in one round
 * trip to the database. For a learning project three queries is fine, but in production with
 * thousands of jobs per second, one query per job instead of three is meaningful.
 */
public interface ReminderJobRepository extends JpaRepository<ReminderJob, Long> {

	/**
	 * Loads a job with its reminder and user in a single query. Used by the worker when it
	 * needs the full context to build a notification.
	 */
	@Query("SELECT j FROM ReminderJob j JOIN FETCH j.reminder r JOIN FETCH r.user WHERE j.id = :id")
	Optional<ReminderJob> findByIdWithReminderAndUser(@Param("id") Long id);

}
