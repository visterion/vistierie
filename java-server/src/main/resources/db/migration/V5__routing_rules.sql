CREATE TABLE vistierie.routing_rules (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES vistierie.tenants(id) ON DELETE CASCADE,
    realm           TEXT,
    purpose         TEXT,
    provider        TEXT NOT NULL,
    model           TEXT NOT NULL,
    priority        INTEGER NOT NULL DEFAULT 100,
    allow_override  BOOLEAN NOT NULL DEFAULT FALSE,
    locked          BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, realm, purpose),
    CHECK (priority >= 0 AND priority <= 10000)
);

CREATE INDEX routing_rules_lookup_idx
    ON vistierie.routing_rules (tenant_id, priority);

CREATE TABLE vistierie.routing_rules_audit (
    id          BIGSERIAL PRIMARY KEY,
    rule_id     UUID NOT NULL,
    tenant_id   UUID NOT NULL,
    action      TEXT NOT NULL CHECK (action IN ('create', 'update', 'delete')),
    before_json JSONB,
    after_json  JSONB,
    set_by      TEXT NOT NULL,
    at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX routing_rules_audit_rule_idx
    ON vistierie.routing_rules_audit (rule_id);
CREATE INDEX routing_rules_audit_tenant_idx
    ON vistierie.routing_rules_audit (tenant_id, at DESC);
