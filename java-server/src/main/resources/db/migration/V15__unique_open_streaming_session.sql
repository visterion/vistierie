-- Enforce "at most one open streaming session per agent".
--
-- StreamingSessionRepository.findOpenByAgent reads with .optional(), so a second open
-- row for the same agent is not merely untidy: every subsequent lookup for that agent
-- throws IncorrectResultSizeDataAccessException until someone closes one by hand.
-- Until now the invariant was only assumed. StreamingSessionCoordinator.handleTick
-- checks for an open session and then inserts one, with no transaction, lock or
-- constraint in between, so two concurrent callers could both pass the check.
--
-- The predicate below already existed as a plain index with exactly the right scope;
-- this makes it unique. Uniqueness stays restricted to open sessions so an agent can
-- still run any number of sessions over time.
DROP INDEX vistierie.idx_streaming_sessions_open;

CREATE UNIQUE INDEX idx_streaming_sessions_open
    ON vistierie.streaming_sessions(agent_id)
    WHERE status = 'open';
