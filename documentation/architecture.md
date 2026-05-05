# Architecture

## Slice 1 scope

Slice 1 is the **LLM gateway** only. It covers:

- Tenant management and bearer-token auth
- Kill-switch (per-tenant, with optional expiry)
- Routing (purpose-keyed model selection per tenant)
- Two synchronous endpoints: `POST /llm/complete` and `POST /llm/vision`
- Anthropic provider (RestClient, no SDK)
- Full audit log in `vistierie.llm_calls`
- Liveness / readiness probes

**Out of scope in Slice 1:** agent registration, run management, webhook
dispatch, scheduler. These are planned for Slice 2, which will add the
`agents`, `runs`, and `run_events` tables.

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

## Slice 2 preview

Slice 2 will add:

- `agents` table — registered webhook endpoints with health-check URLs
- `runs` / `run_events` tables — parent/child run hierarchy
- Webhook dispatcher — delivers agent activation payloads with retry
- Scheduler — cron-based wake-ups firing webhooks on schedule
