SET search_path TO vistierie;

CREATE TABLE agents (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    name            TEXT NOT NULL,
    system_prompt   TEXT NOT NULL,
    model_purpose   TEXT NOT NULL,
    tools           JSONB NOT NULL,
    output_schema   JSONB,
    max_turns       INTEGER NOT NULL DEFAULT 25,
    max_run_seconds INTEGER NOT NULL DEFAULT 1800,
    webhook_token   TEXT NOT NULL,
    paused          BOOLEAN NOT NULL DEFAULT FALSE,
    version         INTEGER NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);
CREATE INDEX agents_tenant_idx ON agents (tenant_id);

CREATE TABLE runs (
    id                        TEXT PRIMARY KEY,
    tenant_id                 UUID NOT NULL REFERENCES tenants(id),
    agent_id                  UUID NOT NULL REFERENCES agents(id),
    agent_snapshot            JSONB NOT NULL,
    agent_version             INTEGER NOT NULL,
    parent_run_id             TEXT REFERENCES runs(id),
    trigger                   TEXT NOT NULL,
    status                    TEXT NOT NULL,
    payload                   JSONB,
    messages_snapshot         JSONB NOT NULL DEFAULT '[]'::jsonb,
    output                    JSONB,
    summary                   TEXT,
    error                     TEXT,
    completion_webhook        TEXT,
    completion_webhook_token  TEXT,
    started_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at               TIMESTAMPTZ
);
CREATE INDEX runs_tenant_started_idx  ON runs (tenant_id, started_at DESC);
CREATE INDEX runs_parent_idx          ON runs (parent_run_id);
CREATE INDEX runs_status_idx          ON runs (status);

CREATE TABLE run_events (
    id        BIGSERIAL PRIMARY KEY,
    run_id    TEXT NOT NULL REFERENCES runs(id),
    ts        TIMESTAMPTZ NOT NULL DEFAULT now(),
    level     TEXT NOT NULL,
    type      TEXT NOT NULL,
    payload   JSONB
);
CREATE INDEX run_events_run_ts_idx ON run_events (run_id, ts);

ALTER TABLE llm_calls
    ADD COLUMN run_id TEXT REFERENCES runs(id);
CREATE INDEX llm_calls_run_id_idx ON llm_calls (run_id);
