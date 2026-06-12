-- Per-agent output-token cap. NULL means "use the runtime default" (see
-- AgentRunner.DEFAULT_MAX_TOKENS). Previously the per-turn max_tokens was a
-- hardcoded 1024 in AgentRunner, which truncated agents whose reasoning or
-- tool-call output ran longer (observed across both HiveMem and Dracul as
-- `no_tool_use: stop_reason=max_tokens` run failures).
ALTER TABLE vistierie.agents
  ADD COLUMN max_tokens INTEGER;
