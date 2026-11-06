-- Stage 3 — Reminders are scheduled work stored as data (not one cron job per user).
CREATE TABLE reminders (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT       NOT NULL REFERENCES users (id),
    title          VARCHAR(255) NOT NULL,
    description    TEXT,
    scheduled_time TIMESTAMPTZ  NOT NULL,
    status         VARCHAR(32)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_reminders_status CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED'))
);

-- Lookups by owner (REST list filter) and by scheduler (status + due time).
CREATE INDEX idx_reminders_user_id ON reminders (user_id);

CREATE INDEX idx_reminders_status_scheduled_time ON reminders (status, scheduled_time);
