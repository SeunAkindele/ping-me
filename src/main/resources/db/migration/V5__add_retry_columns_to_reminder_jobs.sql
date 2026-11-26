-- Stage 7 — Retry handling for the reminder_jobs queue.
--
-- Stage 6 gave every job one chance: a failed send ended in FAILED. Real delivery channels
-- (SMTP, push gateways, SMS providers) fail transiently all the time: timeouts, rate limits,
-- 503s. Most of those failures go away if you simply try again a little later.
--
-- This migration adds the bookkeeping a job needs to be retried safely:
--
--   attempt_count   — how many times a worker has claimed this job. Incremented on claim, not
--                     on failure, so a job that crashes the worker mid-send still counts as an
--                     attempt. That stops one "poison" job from being retried forever.
--   last_error      — the most recent failure reason. This is what an operator reads when a
--                     job lands in the dead letter queue.
--   next_attempt_at — the earliest moment a worker may pick the job up again. Exponential
--                     backoff is implemented by pushing this timestamp into the future.
--   started_at      — when the current attempt was claimed. Lets the worker detect jobs stuck
--                     in PROCESSING because the JVM crashed between claim and completion.
--
-- Status semantics after Stage 7:
--   QUEUED     — waiting for its first attempt OR for a retry (next_attempt_at decides when).
--   PROCESSING — claimed by a worker; started_at says when.
--   COMPLETED  — delivered. Terminal.
--   FAILED     — retries exhausted or permanent error. Terminal. These rows ARE the dead
--                letter queue: they are kept, never deleted, so they can be inspected and replayed.
--
-- The existing status CHECK constraint already allows these four values, so it is unchanged.

ALTER TABLE reminder_jobs
    ADD COLUMN attempt_count   INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN last_error      TEXT,
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ADD COLUMN started_at      TIMESTAMPTZ,
    ADD CONSTRAINT chk_reminder_jobs_attempt_count CHECK (attempt_count >= 0);

-- Backfill: jobs created before this migration become eligible at their creation time,
-- preserving the FIFO order the Stage 6 worker relied on.
UPDATE reminder_jobs SET next_attempt_at = created_at;

-- Worker claim path, rebuilt for retries. The worker now asks:
--   "QUEUED jobs whose next_attempt_at has arrived, oldest first."
-- For a brand-new job next_attempt_at = created_at, so ordering is still FIFO for first
-- attempts. A retried job is ordered by when it becomes eligible, not by when it was created.
DROP INDEX idx_reminder_jobs_queued;
CREATE INDEX idx_reminder_jobs_queued
    ON reminder_jobs (next_attempt_at, id)
    WHERE status = 'QUEUED';

-- Stuck-job recovery path: "PROCESSING jobs claimed before <cutoff>".
-- Partial index keeps this scan tiny — normally only a handful of rows are in flight.
CREATE INDEX idx_reminder_jobs_processing
    ON reminder_jobs (started_at, id)
    WHERE status = 'PROCESSING';
