SET search_path TO vistierie;

ALTER TABLE agents
    ADD COLUMN schedule     TEXT,
    ADD COLUMN last_tick_at TIMESTAMPTZ;

CREATE INDEX agents_scheduled_idx
    ON agents (id)
    WHERE schedule IS NOT NULL AND NOT paused;
