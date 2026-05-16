SET search_path TO vistierie;

CREATE TABLE tenant_budgets (
    tenant_id             UUID PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    daily_cap_micros      BIGINT,
    monthly_cap_micros    BIGINT,
    daily_warn_percent    INTEGER,
    monthly_warn_percent  INTEGER,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT tenant_budgets_daily_warn_percent_chk
        CHECK (daily_warn_percent IS NULL OR daily_warn_percent BETWEEN 1 AND 100),
    CONSTRAINT tenant_budgets_monthly_warn_percent_chk
        CHECK (monthly_warn_percent IS NULL OR monthly_warn_percent BETWEEN 1 AND 100)
);

CREATE TABLE agent_budgets (
    agent_id              UUID PRIMARY KEY REFERENCES agents(id) ON DELETE CASCADE,
    daily_cap_micros      BIGINT,
    monthly_cap_micros    BIGINT,
    daily_warn_percent    INTEGER,
    monthly_warn_percent  INTEGER,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT agent_budgets_daily_warn_percent_chk
        CHECK (daily_warn_percent IS NULL OR daily_warn_percent BETWEEN 1 AND 100),
    CONSTRAINT agent_budgets_monthly_warn_percent_chk
        CHECK (monthly_warn_percent IS NULL OR monthly_warn_percent BETWEEN 1 AND 100)
);

ALTER TABLE llm_calls
    ADD COLUMN agent_id UUID REFERENCES agents(id) ON DELETE SET NULL;

UPDATE vistierie.llm_calls c
SET agent_id = r.agent_id
FROM vistierie.runs r
WHERE c.run_id = r.id
  AND c.agent_id IS NULL;

CREATE INDEX llm_calls_agent_time_idx
    ON llm_calls (agent_id, created_at DESC)
    WHERE agent_id IS NOT NULL;

CREATE INDEX llm_calls_tenant_agent_time_idx
    ON llm_calls (tenant_id, agent_id, created_at DESC)
    WHERE agent_id IS NOT NULL;
