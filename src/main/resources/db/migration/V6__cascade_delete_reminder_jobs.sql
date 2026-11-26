-- Stage 7 follow-up — Deleting a reminder also deletes its jobs.
--
-- The problem:
--   V4 created reminder_jobs.reminder_id as a plain foreign key. PostgreSQL's default
--   behaviour for a plain FK is ON DELETE NO ACTION: it refuses to delete a parent row
--   (reminder) while child rows (jobs) still point at it. So once the scheduler had enqueued
--   a job, DELETE /reminders/{id} failed with a foreign-key violation (HTTP 500).
--
-- The fix: ON DELETE CASCADE.
--   When a reminder row is deleted, PostgreSQL deletes every reminder_jobs row that
--   references it, in the same statement and the same transaction. Either both go or neither.
--
-- Why cascade here is the right call:
--   A job only exists to deliver its reminder. A job without a reminder is meaningless: it
--   cannot be processed and no one can query it through the API. Ownership is total, so the
--   lifecycle should be too. (Contrast users → reminders: there we deliberately do NOT
--   cascade, because silently wiping a user's whole history is a decision the application
--   should make explicitly.)
--
-- Why in the database instead of JPA (@OneToMany(cascade = REMOVE)):
--   - The database enforces it for every client: the API, a psql session, a future admin tool.
--   - One DELETE statement, instead of Hibernate loading every job into memory and deleting
--     them one by one.
--   - ReminderJob → Reminder is mapped one-way (@ManyToOne only). Reminder does not need to
--     know jobs exist, and adding a collection just to get cascade would couple them.
--
-- Trade-off to understand:
--   Deleting a reminder also deletes its delivery history, including dead-lettered (FAILED)
--   jobs. For a learning project that is fine. A production system that needs an audit
--   trail would soft-delete reminders (a deleted_at column) instead of removing rows.
--
-- Race with the worker:
--   If a reminder is deleted while its job is being processed, the worker's final UPDATE
--   finds no row / processJob cannot load the job. The worker logs the error and moves on;
--   at worst the user gets the notification they were just deleting. Acceptable here.
--
-- Constraint name: V4 did not name the FK, so PostgreSQL generated the default
-- "<table>_<column>_fkey". We replace it with an explicitly named constraint so future
-- migrations can refer to it reliably.

ALTER TABLE reminder_jobs
    DROP CONSTRAINT reminder_jobs_reminder_id_fkey;

ALTER TABLE reminder_jobs
    ADD CONSTRAINT fk_reminder_jobs_reminder
        FOREIGN KEY (reminder_id) REFERENCES reminders (id) ON DELETE CASCADE;
