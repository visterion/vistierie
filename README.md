# Vistierie

Standalone LLM gateway, subagent framework, and scheduler.

**Status:** v1 design phase. No code yet.

## What it is

One Java/Spring Boot service that consumer apps (HiveMem, Draczl, future
tenants) talk to over REST + webhooks for:

- **LLM gateway** — provider-abstracted `complete` / `embed` / `vision`
  with policy-driven routing per tenant / realm / purpose.
- **Subagent framework** — register agents, dispatch via webhook,
  reconstruct parent/child run hierarchy.
- **Scheduler** — cron-based wake-ups firing webhooks on schedule.
- **Cost tracking + audit** — every LLM call and agent run logged.
- **Kill-switch** — central point to stop autonomous activity.

It is **not** an MCP server, workflow engine, prompt library, or vector
store. Prompts live with the consumer.

## License

Apache-2.0 (planned).
