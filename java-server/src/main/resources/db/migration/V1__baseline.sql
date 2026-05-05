CREATE SCHEMA IF NOT EXISTS vistierie;
SET search_path TO vistierie;

CREATE TABLE tenants (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name          TEXT NOT NULL UNIQUE,
    token_hash    TEXT NOT NULL,
    kill_until    TIMESTAMPTZ,
    kill_reason   TEXT,
    kill_set_by   TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE llm_calls (
    id                              TEXT PRIMARY KEY,
    tenant_id                       UUID NOT NULL REFERENCES tenants(id),
    purpose                         TEXT NOT NULL,
    realm                           TEXT,
    provider                        TEXT NOT NULL,
    model                           TEXT NOT NULL,
    endpoint                        TEXT NOT NULL,
    input_tokens                    INTEGER NOT NULL DEFAULT 0,
    output_tokens                   INTEGER NOT NULL DEFAULT 0,
    cache_creation_input_tokens     INTEGER NOT NULL DEFAULT 0,
    cache_read_input_tokens         INTEGER NOT NULL DEFAULT 0,
    cost_micros                     BIGINT  NOT NULL DEFAULT 0,
    duration_ms                     INTEGER NOT NULL DEFAULT 0,
    status                          TEXT NOT NULL,
    error_code                      TEXT,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX llm_calls_tenant_time_idx
    ON llm_calls (tenant_id, created_at DESC);
CREATE INDEX llm_calls_tenant_purpose_time_idx
    ON llm_calls (tenant_id, purpose, created_at DESC);
