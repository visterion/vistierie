# Architecture

## Scope (Slices 1 + 2)

- **Slice 1 — gateway:** tenants, auth, kill-switch, routing, synchronous
  `POST /llm/complete` and `POST /llm/vision`, Anthropic provider, audit log.
- **Slice 2 — agent framework:** tenant-scoped agent registration,
  asynchronous run execution with parallel HTTP tools and recursive
  subagents (with context shielding), long-poll, completion webhook,
  per-run event timeline, opt-in stress harness.

**Out of scope:** scheduler/cron triggers, multi-tenant analytics dashboard,
self-serve routing config (still operator-edited).

---

## Data model (Slice 1)

Two tables in the `vistierie` schema (see `V1__baseline.sql`):

```
tenants
  id          UUID PK
  name        TEXT UNIQUE
  token_hash  TEXT          -- bcrypt of plaintext token
  kill_until  TIMESTAMPTZ   -- null = not killed
  kill_reason TEXT
  kill_set_by TEXT
  created_at  TIMESTAMPTZ

llm_calls
  id                           TEXT PK   -- ULID
  tenant_id                    UUID FK → tenants.id
  purpose                      TEXT
  realm                        TEXT
  provider                     TEXT
  model                        TEXT
  endpoint                     TEXT      -- "/llm/complete" or "/llm/vision"
  input_tokens                 INTEGER
  output_tokens                INTEGER
  cache_creation_input_tokens  INTEGER
  cache_read_input_tokens      INTEGER
  cost_micros                  BIGINT
  duration_ms                  INTEGER
  status                       TEXT      -- "ok" | error code
  error_code                   TEXT
  created_at                   TIMESTAMPTZ
```

---

## Request flow

```
HiveMem (AnthropicSummarizer / VisionClient)
   │  POST /llm/complete | /llm/vision   (tenant bearer token)
   ▼
Vistierie ── auth filter ── kill check ── routing ── AnthropicProvider ── api.anthropic.com
                                                          │
                                                          └── audit row → llm_calls
```

Step by step:

1. `AuthFilter` extracts the `Authorization: Bearer` token, matches it against
   `tenants.token_hash` via BCrypt, and stores the resolved `Tenant` in request
   context.
2. `LlmService` checks the kill-switch (`kill_until > now()`); if active it
   returns `403` immediately.
3. `RoutingResolver` looks up the purpose in the tenant's routing config and
   returns a `RoutingDecision` (provider + model + allow-override flag).
4. `ProviderRegistry` dispatches to `AnthropicProvider` (or `MockProvider` in
   mock-LLM mode).
5. `LlmCallRecorder` writes one row to `vistierie.llm_calls` regardless of
   whether the provider call succeeded or failed.
6. The response DTO is returned to the caller.

---

## Deployment topology (v1)

Vistierie is co-located with its consumers (HiveMem, Draczl) on one host,
communicating over a private Docker network. No TLS or HMAC between services
in v1 — the private network is the trust boundary (see spec §8.1).

Production image: `ghcr.io/vesterion/vistierie:main`  
Listen port: `8090`

---

## Agent framework (Slice 2)

Tables added in `V2__agents_runs_run_events.sql`: `agents`, `runs`,
`run_events`. The `llm_calls` table gained a nullable `run_id` column so
LLM call audits can be aggregated per agent run.

```
consumer ── POST /agents/{name}/run ──▶ RunController
                                         │
                                         ▼
                                  AgentDispatcher  (writes runs row, queues async)
                                         │
                                         ▼
                                    AgentRunner  (virtual-thread executor)
                                  ┌──────┴───────┐
                                  ▼              ▼
                          ToolDispatcher    AgentRunner (recursive child run)
                          (parallel HTTP)   (context shielded — only output
                                             is returned to parent)
                                  │              │
                                  ▼              ▼
                            consumer       AnthropicProvider
                          tool webhooks    (LlmCallRecorder ─▶ llm_calls)
```

Context shielding: a parent run never sees a child's system prompt, turns,
or intermediate tool calls — only the validated structured `output`. See
[agents.md](agents.md) for the agent definition and tool format.

Long-polls and completion webhooks are in-process (no Redis, no DB queue);
a Vistierie restart drops open polls and re-fires pending webhooks based on
the persisted run state.
