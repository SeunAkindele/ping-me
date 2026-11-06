-- Stage 6 — The reminder_jobs table is a database-backed queue.
--
-- In production systems, queues live in RabbitMQ, SQS, or Kafka. Here PostgreSQL plays
-- that role so you learn the producer/consumer pattern without adding infrastructure.
--
-- The scheduler (producer) inserts a QUEUED row when it claims a due reminder.
-- The worker (consumer) locks QUEUED rows, processes them, and marks them COMPLETED or FAILED.
-- This separation means the scheduler finishes fast while the worker handles slow I/O.
--
-- Why a separate table instead of extra columns on reminders?
--   1. The reminder is a business entity (user's data). The job is an operational artifact.
--   2. The job table is purpose-built for the worker: fast scans, independent lifecycle.
--   3. Stage 7 can add retry fields (attempt_count, last_error) without touching reminders.
--   4. You get an audit trail: every processing attempt is a row, not an overwritten status.
CREATE TABLE reminder_jobs (
    id           BIGSERIAL    PRIMARY KEY,
    reminder_id  BIGINT       NOT NULL REFERENCES reminders (id),
    status       VARCHAR(32)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMPTZ,
    CONSTRAINT chk_reminder_jobs_status CHECK (status IN ('QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

-- Worker claim path: find the oldest QUEUED jobs first (FIFO order).
-- Partial index covers only QUEUED rows so completed/failed jobs don't slow down the scan.
-- This is the consumer-side equivalent of idx_reminders_pending_due (V3).
CREATE INDEX idx_reminder_jobs_queued
    ON reminder_jobs (created_at, id)
    WHERE status = 'QUEUED';

-- Lookup jobs by reminder (e.g., to check if a reminder already has a pending job).
CREATE INDEX idx_reminder_jobs_reminder_id ON reminder_jobs (reminder_id);
