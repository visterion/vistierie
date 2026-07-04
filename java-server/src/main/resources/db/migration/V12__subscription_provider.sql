-- V12__subscription_provider.sql
-- Routing fallback: optional one-step fallback provider+model per rule.
ALTER TABLE vistierie.routing_rules
    ADD COLUMN fallback_provider TEXT,
    ADD COLUMN fallback_model    TEXT;

ALTER TABLE vistierie.routing_rules
    ADD CONSTRAINT routing_rules_fallback_pair
        CHECK ((fallback_provider IS NULL) = (fallback_model IS NULL));

-- Shadow cost: what a subscription-served call would have cost via the API.
-- NULL for all calls served by API-key providers.
ALTER TABLE vistierie.llm_calls
    ADD COLUMN shadow_cost_micros BIGINT;
