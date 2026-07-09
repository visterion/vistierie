# Architecture

## Scope (v1.0)

- **Gateway:** tenants, auth, kill-switch, routing, synchronous
  `POST /llm/complete` and `POST /llm/vision`, Anthropic / OpenAI-compatible
  (OpenAI, xAI, …) / Bedrock providers, per-call audit log with
  token-accurate EUR-micros cost, and
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

The `vistierie` schema holds the following tables, created across migrations
V1–V12 (V12 adds columns only, no new tables). The 13 application tables are: `tenants`, `llm_calls`, `agents`,
`runs`, `run_events`, `routing_rules`, `routing_rules_audit`,
`llm_call_bodies`, `tenant_budgets`, `agent_budgets`, `streaming_sessions`,
`run_tool_calls`, `run_search_doc`. (Plus Flyway's `flyway_schema_history`.)

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
  run_id                       TEXT FK → runs.id (nullable, ON DELETE SET NULL)  -- V2
  batch_id                     TEXT      -- nullable, V4
  shadow_cost_micros           BIGINT    -- V12, nullable; what a subscription-served call would have cost via the API
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

agents
  id                        UUID PK
  tenant_id                 UUID FK → tenants.id (ON DELETE CASCADE)
  name                      TEXT
  system_prompt             TEXT
  model_purpose             TEXT
  tools                     JSONB
  output_schema             JSONB         -- nullable
  max_turns                 INTEGER       -- default 25
  max_run_seconds           INTEGER       -- default 1800
  webhook_token             TEXT
  paused                    BOOLEAN       -- default false
  version                   INTEGER       -- default 1
  schedule                  TEXT          -- V3
  last_tick_at              TIMESTAMPTZ   -- V3
  completion_webhook        TEXT          -- V8
  completion_webhook_token  TEXT          -- V8
  event_source_url          TEXT          -- V9
  session_duration_seconds  INTEGER       -- V9
  poll_interval_seconds     INTEGER       -- V9
  max_tokens                INTEGER       -- V10, nullable → runtime default 8192
  mcp_credentials           JSONB         -- V13, default '{}' — { "<mcp_server_url>": "<bearer_token>" }
  created_at                TIMESTAMPTZ
  updated_at                TIMESTAMPTZ
  UNIQUE (tenant_id, name)

runs
  id                        TEXT PK   -- ULID
  tenant_id                 UUID FK → tenants.id (ON DELETE CASCADE)
  agent_id                  UUID FK → agents.id (ON DELETE CASCADE)
  agent_snapshot            JSONB
  agent_version             INTEGER
  parent_run_id             TEXT FK → runs.id (ON DELETE SET NULL)
  trigger                   TEXT
  status                    TEXT
  payload                   JSONB
  messages_snapshot         JSONB         -- default '[]'
  output                    JSONB
  summary                   TEXT
  error                     TEXT
  completion_webhook        TEXT
  completion_webhook_token  TEXT
  anthropic_batch_id        TEXT          -- V4
  session_id                UUID          -- V9, nullable
  started_at                TIMESTAMPTZ
  finished_at               TIMESTAMPTZ

run_events
  id        BIGSERIAL PK
  run_id    TEXT FK → runs.id (ON DELETE CASCADE)
  ts        TIMESTAMPTZ
  level     TEXT
  type      TEXT
  payload   JSONB

llm_call_bodies
  call_id                TEXT PK FK → llm_calls.id (ON DELETE CASCADE)
  request_json           JSONB
  response_text          TEXT          -- nullable
  created_at             TIMESTAMPTZ
  response_content_json  JSONB         -- V11, nullable

streaming_sessions
  id            UUID PK
  tenant_id     UUID
  agent_id      UUID FK → agents.id
  opened_at     TIMESTAMPTZ
  closes_at     TIMESTAMPTZ
  last_poll_at  TIMESTAMPTZ   -- nullable
  status        TEXT
  created_at    TIMESTAMPTZ
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

Optionally, the `claude-bridge` sidecar (a TypeScript service wrapping the
Claude Agent SDK) runs on the same private network at `claude-bridge:8091`,
published as `ghcr.io/visterion/vistierie-claude-bridge` with the same tag
scheme. Vistierie's `ClaudeSubscriptionProvider` calls it over plain HTTP
when `vistierie.claude-subscription.enabled=true`, routing calls through a
Claude subscription (OAuth token) instead of the pay-per-token Anthropic API.
See [providers.md](providers.md) and
[operations.md](operations.md#claude-bridge-subscription-provider).

---

## Routing rules (Slice 6)

Tables added in `V5__routing_rules.sql`:

```
routing_rules
  id              UUID PK
  tenant_id       UUID FK → tenants.id
  realm           TEXT      -- NULL = match any realm
  purpose         TEXT      -- NULL = match any purpose
  provider        TEXT
  model           TEXT
  priority        INTEGER   -- lower wins; default 100, CHECK BETWEEN 0 AND 10000
  allow_override  BOOLEAN
  locked          BOOLEAN   -- when true, ignores allow_override
  fallback_provider TEXT    -- V12, nullable; one-step fallback if the primary call fails
  fallback_model    TEXT    -- V12, nullable; CHECK (fallback_provider IS NULL) = (fallback_model IS NULL)
  created_at      TIMESTAMPTZ
  updated_at      TIMESTAMPTZ

routing_rules_audit
  id              BIGSERIAL PK
  rule_id         UUID      -- bare UUID, no FK (survives deletes)
  tenant_id       UUID
  action          TEXT      -- CHECK IN ('create', 'update', 'delete')
  before_json     JSONB
  after_json      JSONB
  set_by          TEXT
  at              TIMESTAMPTZ   -- default now()
```

Schema overview (all tables under `vistierie`):

- `tenants`          : registered tenants, bcrypt token hash, kill-switch state
- `llm_calls`        : per-call audit (tokens, cost, provider, model, agent link, run link)
- `agents`           : tenant-scoped agent definitions (incl. per-agent `max_tokens` output cap, nullable → runtime default)
- `runs`             : agent run lifecycle
- `run_events`       : append-only event timeline per run
- `routing_rules`    : operator-managed routing policy (per tenant, realm, purpose)
- `routing_rules_audit`: append-only history of admin writes to routing_rules
- `llm_call_bodies`  : full request JSON (vision blobs redacted to sha256 stub), response text, and (V11) full response content blocks per LLM call; cleaned by BodyRetentionJob
- `tenant_budgets`   : operator-managed hard tenant caps and optional warn thresholds
- `agent_budgets`    : operator-managed hard per-agent caps and optional warn thresholds
- `run_tool_calls`   : (V11) one row per tool invocation per run — captures input/output, tool type, and failure detail
- `run_search_doc`   : (V11) per-run full-text search document (`tsvector`, `simple` config); built on run completion

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

---

## Run-transcript observability (V11)

Migration: `V11__run_transcript.sql`.

### Scope boundary

Vistierie **captures and exposes** run transcripts (write-path + read API +
full-text search). It performs **no analysis** — that is the consumer's
responsibility. Historical read-back only; no live streaming.

### Data model additions

#### `llm_call_bodies.response_content_json` (new column)

```
llm_call_bodies
  ...existing columns...
  response_content_json  JSONB   -- full response content blocks (text + tool_use) per LLM call
```

Stores the raw content block array returned by the provider (e.g. Anthropic's
`content` array containing `text` and `tool_use` blocks). `NULL` for calls
made before V11 or when capture fails. Exposed via `GET /runs/{id}/transcript?view=full`.

#### `run_tool_calls` (new table)

One row per tool invocation per run, written by `ToolCallCaptureService`
immediately after each tool dispatch.

```
run_tool_calls
  id            TEXT PK   -- ULID (newUlid())
  run_id        TEXT FK → runs.id
  tenant_id     UUID FK → tenants.id
  llm_call_id   TEXT FK → llm_calls.id (nullable)
  turn_index    INTEGER       -- zero-based turn within the run
  tool_use_id   TEXT          -- Anthropic tool_use block id (e.g. "toolu_01A…")
  tool_name     TEXT
  tool_type     TEXT          -- "http" | "subagent" | "unknown"
  input_json    JSONB
  output_json   JSONB
  is_error      BOOLEAN
  error_detail  TEXT          -- populated on hard failures (4xx/5xx/timeout)
  created_at    TIMESTAMPTZ
```

**Failure visibility:**
- **Hard failures** (HTTP 4xx / 5xx / timeout): `is_error = true`,
  `error_detail` contains the error message.
- **Graceful-empty outputs** (e.g. HTTP 200 returning an empty payload because
  a downstream API key is missing): `is_error = false`, `output_json` faithfully
  captures the empty payload. The *reason* must be surfaced by the consumer's
  tool — Vistierie cannot see inside it.

Capture is **best-effort**: a capture failure never fails a run.

#### `run_search_doc` (new table)

Per-run full-text search document, built on run completion by `RunStore.markTerminal`.

```
run_search_doc
  run_id      TEXT PK FK → runs.id (ON DELETE CASCADE)
  tenant_id   UUID FK → tenants.id (ON DELETE CASCADE)
  agent_id    UUID          -- nullable, no FK
  agent_name  TEXT
  status      TEXT NOT NULL
  has_error   BOOLEAN NOT NULL  -- default false
  started_at  TIMESTAMPTZ NOT NULL
  body        TEXT NOT NULL
  tsv         tsvector      -- to_tsvector('simple', body), GIN-indexed
  excerpt     TEXT          -- left(body, 500)
```

The `body` text (and its `tsv` vector) indexes agent name, status, run output,
tool names, tool input/output text, and response text. Used by `GET /runs/search` and
`GET /admin/runs/search` (ranked hits with snippets).

### Read API

See [api.md](api.md) for the full endpoint reference:

- `GET /runs/{id}/transcript?view=digest|compact|full` — turn-by-turn transcript,
  three verbosity levels; `compact` is the default.
- `GET /runs/{id}/tool-calls/{toolUseId}` — single tool call, untruncated.
- `GET /admin/runs/{id}/transcript` — admin variant, cross-tenant.
- `GET /runs/search` and `GET /admin/runs/search` — ranked full-text search,
  snippet-only results.
