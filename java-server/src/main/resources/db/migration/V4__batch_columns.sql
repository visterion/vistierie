SET search_path TO vistierie;

ALTER TABLE llm_calls
    ADD COLUMN batch_id TEXT;

ALTER TABLE runs
    ADD COLUMN anthropic_batch_id TEXT;

CREATE INDEX llm_calls_batch_idx
    ON llm_calls (batch_id)
    WHERE batch_id IS NOT NULL;

CREATE INDEX runs_anthropic_batch_idx
    ON runs (anthropic_batch_id)
    WHERE anthropic_batch_id IS NOT NULL AND status IN ('queued','running');
