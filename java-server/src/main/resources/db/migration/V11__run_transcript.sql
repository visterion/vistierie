-- V11: run-transcript observability — capture + search

-- 1. Lossless raw LLM response content blocks (text + tool_use + thinking) per call.
ALTER TABLE vistierie.llm_call_bodies
    ADD COLUMN response_content_json JSONB;

-- 2. First-class tool-call capture: input, output (incl. empty), and failure detail.
CREATE TABLE vistierie.run_tool_calls (
    id            TEXT PRIMARY KEY,
    run_id        TEXT NOT NULL REFERENCES vistierie.runs(id) ON DELETE CASCADE,
    tenant_id     UUID NOT NULL REFERENCES vistierie.tenants(id) ON DELETE CASCADE,
    llm_call_id   TEXT REFERENCES vistierie.llm_calls(id) ON DELETE SET NULL,
    turn_index    INTEGER NOT NULL,
    tool_use_id   TEXT NOT NULL,
    tool_name     TEXT NOT NULL,
    tool_type     TEXT NOT NULL,
    input_json    JSONB,
    output_json   JSONB,
    is_error      BOOLEAN NOT NULL DEFAULT false,
    error_detail  TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX run_tool_calls_run_turn_idx     ON vistierie.run_tool_calls (run_id, turn_index);
CREATE INDEX run_tool_calls_tenant_error_idx ON vistierie.run_tool_calls (tenant_id, is_error);

-- 3. Per-run full-text search document, built on run completion.
CREATE TABLE vistierie.run_search_doc (
    run_id      TEXT PRIMARY KEY REFERENCES vistierie.runs(id) ON DELETE CASCADE,
    tenant_id   UUID NOT NULL REFERENCES vistierie.tenants(id) ON DELETE CASCADE,
    agent_id    UUID,
    agent_name  TEXT,
    status      TEXT NOT NULL,
    has_error   BOOLEAN NOT NULL DEFAULT false,
    started_at  TIMESTAMPTZ NOT NULL,
    body        TEXT NOT NULL,
    tsv         tsvector,
    excerpt     TEXT
);
CREATE INDEX run_search_doc_tsv_idx            ON vistierie.run_search_doc USING GIN (tsv);
CREATE INDEX run_search_doc_tenant_started_idx ON vistierie.run_search_doc (tenant_id, started_at DESC);
CREATE INDEX run_search_doc_tenant_agent_idx   ON vistierie.run_search_doc (tenant_id, agent_name);
