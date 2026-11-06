-- Stage 5 — Claim path: pending rows, oldest due time first, then id.
-- Claimed rows leave PENDING, so they drop out of this partial index.
-- idx_reminders_status_scheduled_time (V2) still covers lookups by any status.
CREATE INDEX idx_reminders_pending_due
    ON reminders (scheduled_time, id)
    WHERE status = 'PENDING';
