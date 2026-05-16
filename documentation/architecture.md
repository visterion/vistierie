# Architecture

## Scope (v1.0)

- **Gateway:** tenants, auth, kill-switch, routing, synchronous
  `POST /llm/complete` and `POST /llm/vision`, Anthropic / OpenAI / xAI
  providers, per-call audit log with token-accurate EUR-micros cost, and
  hard tenant/agent budgets on every billable path.
- **Agent framework:** tenant-scoped agent registration, asynchronous
  run execution with parallel HTTP tools and recursive subagents (with
  context shielding), long-poll, completion webhook, per-run event
  timeline, opt-in stress harness.
- **Scheduler:** cron-driven agent runs with kill-switch awareness and
  no-overlap safety per agent.
- **Batch:** `POST /agents/{name}/batch` via Anthropic Message Batches API.
- **Admin:** DB-backed routing rules with privacy lock, cross-tenant audit
  reads, hourly/daily cost rollups, retention job for stored request bodies.

---

## Data model

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
  agent_id                     UUID FK → agents.id (nullable for historic backfill only)
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

tenant_budgets
  tenant_id                    UUID PK → tenants.id
  daily_cap_micros             BIGINT
  monthly_cap_micros           BIGINT
  daily_warn_percent           INTEGER
  monthly_warn_percent         INTEGER
  created_at                   TIMESTAMPTZ
  updated_at                   TIMESTAMPTZ

agent_budgets
  agent_id                     UUID PK → agents.id
  daily_cap_micros             BIGINT
  monthly_cap_micros           BIGINT
  daily_warn_percent           INTEGER
  monthly_warn_percent         INTEGER
  created_at                   TIMESTAMPTZ
  updated_at                   TIMESTAMPTZ
```

---

## Request flow

```
HiveMem / Dracul / internal callers
   │  POST /llm/complete | /llm/vision   (tenant bearer token + agent_name)
   ▼
Vistierie ── auth filter ── agent resolve ── kill check ── budget check ── routing ── AnthropicProvider ── api.anthropic.com
                                                                                           │
                                                                                           └── audit row → llm_calls
```

Step by step:

1. `AuthFilter` extracts the `Authorization: Bearer` token, matches it against
   `tenants.token_hash` via BCrypt, and stores the resolved `Tenant` in request
   context.
2. `LlmService` resolves `agent_name` inside the authenticated tenant.
3. `LlmService` checks the kill-switch (`kill_until > now()`); if active it
   returns `403` immediately.
4. `BudgetEnforcer` loads tenant and agent budget policy, aggregates current
   day/month usage from `llm_calls.cost_micros`, and blocks when a hard cap is
   missing or exceeded.
5. `RoutingResolver` looks up the purpose in the tenant's routing config and
   returns a `RoutingDecision` (provider + model + allow-override flag).
6. `ProviderRegistry` dispatches to `AnthropicProvider` (or `MockProvider` in
   mock-LLM mode).
7. `LlmCallRecorder` writes one row to `vistierie.llm_calls` regardless of
   whether the provider call succeeded or failed.
8. The response DTO is returned to the caller, including remaining-budget
   headers on direct `/llm/**` success responses when caps are configured.

---

## Deployment topology (v1)

Vistierie is co-located with its consumers (HiveMem, Dracul) on one host,
communicating over a private Docker network. No TLS or HMAC between services
in v1, the private network is the trust boundary (see spec §8.1).

Production image: `ghcr.io/visterion/vistierie:main` (also tagged `latest` and `v1.0.0` on releases)  
Listen port: `8090`

---

## Routing rules (Slice 6)

Tables added in `V5__routing_rules.sql`:

```
routing_rules
  id              BIGSERIAL PK
  tenant_id       UUID FK → tenants.id
  realm           TEXT      -- NULL = match any realm
  purpose         TEXT      -- NULL = match any purpose
  provider        TEXT
  model           TEXT
  priority        INTEGER   -- lower wins; 1000 = tenant default
  allow_override  BOOLEAN
  locked          BOOLEAN   -- when true, ignores allow_override
  created_at      TIMESTAMPTZ
  updated_at      TIMESTAMPTZ

routing_rules_audit
  id              BIGSERIAL PK
  rule_id         BIGINT FK → routing_rules.id (nullable, survives deletes)
  tenant_id       UUID
  action          TEXT      -- "create" | "update" | "delete"
  changed_by      TEXT
  payload         JSONB     -- full snapshot of the rule at write time
  created_at      TIMESTAMPTZ
```

Schema overview (all tables under `vistierie`):

- `tenants`          : registered tenants, bcrypt token hash, kill-switch state
- `llm_calls`        : per-call audit (tokens, cost, provider, model, agent link, run link)
- `agents`           : tenant-scoped agent definitions
- `runs`             : agent run lifecycle
- `run_events`       : append-only event timeline per run
- `routing_rules`    : operator-managed routing policy (per tenant, realm, purpose)
- `routing_rules_audit`: append-only history of admin writes to routing_rules
- `llm_call_bodies`  : full request JSON (vision blobs redacted to sha256 stub) and response text per LLM call; cleaned by BodyRetentionJob
- `tenant_budgets`   : operator-managed hard tenant caps and optional warn thresholds
- `agent_budgets`    : operator-managed hard per-agent caps and optional warn thresholds

---

## Agent framework (Slice 2)

Tables added in `V2__agents_runs_run_events.sql`: `agents`, `runs`,
`run_events`. The `llm_calls` table gained a nullable `run_id` column so
LLM call audits can be aggregated per agent run.

```
consumer ── POST /agents/{name}/run ──▶ RunController
                                         │
                                         ▼
                                  BudgetEnforcer   (tenant + agent readiness gate)
                                         │
                                         ▼
                                  AgentDispatcher  (writes runs row, queues async)
                                         │
                                         ▼
                                    AgentRunner  (virtual-thread executor)
                                  ┌──────┴───────┐
                                  ▼              ▼
                          ToolDispatcher    AgentRunner (recursive child run)
                          (parallel HTTP)   (context shielded, only output
                                             is returned to parent)
                                  │              │
                                  ▼              ▼
                            consumer       AnthropicProvider
                          tool webhooks    (LlmCallRecorder ─▶ llm_calls)
```

Context shielding: a parent run never sees a child's system prompt, turns,
or intermediate tool calls, only the validated structured `output`. See
[agents.md](agents.md) for the agent definition and tool format.

Budget gates also apply beyond manual runs:

- direct `/llm/complete` and `/llm/vision`
- manual `/agents/{name}/run`
- scheduler ticks
- batch submission
- unpausing an agent
- each iterative provider dispatch inside `AgentRunner`

Long-polls and completion webhooks are in-process (no Redis, no DB queue);
a Vistierie restart drops open polls and re-fires pending webhooks based on
the persisted run state.
