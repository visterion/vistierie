CREATE TABLE vistierie.llm_call_bodies (
    call_id       TEXT PRIMARY KEY REFERENCES vistierie.llm_calls(id) ON DELETE CASCADE,
    request_json  JSONB NOT NULL,
    response_text TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX llm_call_bodies_created_idx
    ON vistierie.llm_call_bodies (created_at);
