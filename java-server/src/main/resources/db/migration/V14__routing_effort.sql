-- V14__routing_effort.sql
-- Optional per-rule reasoning effort, forwarded to providers that support it
-- (currently claude-subscription). NULL = provider default behavior.
ALTER TABLE vistierie.routing_rules
    ADD COLUMN effort TEXT;

ALTER TABLE vistierie.routing_rules
    ADD CONSTRAINT routing_rules_effort_check
        CHECK (effort IS NULL OR effort IN ('off', 'low', 'medium', 'high', 'max'));
