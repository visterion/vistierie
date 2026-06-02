-- Streaming Bee: new fields on agents
ALTER TABLE vistierie.agents
  ADD COLUMN event_source_url        TEXT,
  ADD COLUMN session_duration_seconds INT,
  ADD COLUMN poll_interval_seconds    INT;

-- Streaming Bee: session_id on runs (nullable, for traceability)
ALTER TABLE vistierie.runs
  ADD COLUMN session_id UUID;

CREATE INDEX runs_session_id_idx ON vistierie.runs (session_id)
  WHERE session_id IS NOT NULL;

-- Streaming sessions table
CREATE TABLE vistierie.streaming_sessions (
    id           UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL,
    agent_id     UUID NOT NULL REFERENCES vistierie.agents(id),
    opened_at    TIMESTAMPTZ NOT NULL,
    closes_at    TIMESTAMPTZ NOT NULL,
    last_poll_at TIMESTAMPTZ,
    status       TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_streaming_sessions_open
    ON vistierie.streaming_sessions(agent_id)
    WHERE status = 'open';
