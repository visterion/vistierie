# Vistierie REST API

## Overview

Vistierie is a tenant-scoped, audited, kill-switchable LLM gateway and agent
framework. Every call is authenticated with a bearer token, resolved against
a routing policy, forwarded to the configured provider (Anthropic), and
recorded in the `llm_calls` audit table. Every billable LLM call must also
resolve to a concrete tenant-local agent so tenant and per-agent hard budgets
can be enforced consistently.

Two surfaces:
- **Synchronous LLM gateway**: `POST /llm/complete`, `POST /llm/vision`, `POST /llm/vision-multi`.
- **Agent framework**: `POST /agents`, `POST /agents/{name}/run`, `GET /runs/...`.
  See [agents.md](agents.md) for the agent and tool model.

---

## Authentication

All endpoints except `/healthz`, `/readyz`, and `/actuator/**` require:

```
Authorization: Bearer <token>
```

- **`/llm/**`**: tenant bearer token issued via `POST /admin/tenants`.
- **`/admin/**`**: separate admin token configured via `VISTIERIE_ADMIN_TOKEN_HASH`.

Tokens are stored as bcrypt hashes. The plaintext is never persisted. The admin
token hash is set at deploy time (see [configuration.md](configuration.md)).

---

## `POST /llm/complete`

Run a chat completion against the tenant's routed model.

### Request

```json
{
  "agent_name": "summarizer",
  "purpose":    "summarize_cell",
  "realm":      "personal",
  "system":     "You are a concise summarizer.",
  "messages":   [{"role": "user", "content": "Summarize this text…"}],
  "max_tokens": 512,
  "temperature": 0.3,
  "model":      "claude-haiku-4-5"
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `agent_name` | string | yes | Existing agent name in the calling tenant; required for budget enforcement and audit attribution |
| `purpose` | string | yes | Routing key, matched against tenant routing config |
| `realm` | string | no | Informational label; stored in audit log (no routing effect in Slice 1) |
| `system` | string | no | System prompt prepended before `messages` |
| `messages` | array | yes | List of `{role, content}` objects (OpenAI-compatible shape) |
| `max_tokens` | integer | no | Cap on output tokens; routing config may set a lower cap |
| `temperature` | number | no | Sampling temperature (0–1) |
| `model` | string | no | Override the resolved model, only honoured when `allow-override: true` in routing config |

### Response `200 OK`

```json
{
  "text":        "Here is a concise summary…",
  "stop_reason": "end_turn",
  "usage": {
    "inputTokens":                128,
    "outputTokens":               64,
    "cacheCreationInputTokens":   0,
    "cacheReadInputTokens":       0
  },
  "provider":    "anthropic",
  "model":       "claude-haiku-4-5",
  "cost_micros": 142,
  "llm_call_id": "01HZ..."
}
```

| Field | Type | Description |
|---|---|---|
| `text` | string | Generated text |
| `stop_reason` | string | Reason the model stopped (`end_turn`, `max_tokens`, …) |
| `usage.inputTokens` | integer | Prompt tokens consumed |
| `usage.outputTokens` | integer | Completion tokens generated |
| `usage.cacheCreationInputTokens` | integer | Tokens written to Anthropic prompt cache |
| `usage.cacheReadInputTokens` | integer | Tokens read from Anthropic prompt cache |
| `provider` | string | Provider that handled the call |
| `model` | string | Model that handled the call (may differ from request when override is off) |
| `cost_micros` | integer | Estimated cost in micro-EUR (divide by 1 000 000 for EUR) |
| `llm_call_id` | string | Audit row ID, safe to log and reference in support requests |

### Response headers

When the relevant caps are configured, successful direct calls also return:

- `X-Vistierie-Tenant-Daily-Budget-Remaining-Micros`
- `X-Vistierie-Tenant-Monthly-Budget-Remaining-Micros`
- `X-Vistierie-Agent-Daily-Budget-Remaining-Micros`
- `X-Vistierie-Agent-Monthly-Budget-Remaining-Micros`

### Error codes

| HTTP | Meaning |
|---|---|
| 400 | Validation error, malformed body or missing required field |
| 401 | Missing or invalid bearer token, or the bearer's tenant has been deleted mid-request |
| 403 | Tenant kill-switch is active, `agent_name` is unknown, or a tenant/agent budget gate blocked the call |
| 502 | Provider returned an HTTP ≥ 500 error |
| 504 | Provider request timed out |

Budget-related `403` responses are JSON objects with at least:

```json
{
  "error": "budget_exceeded_agent_daily",
  "message": "agent daily budget exceeded for tenant dracul and agent summarizer",
  "tenant": "dracul",
  "agent_name": "summarizer"
}
```

Known budget/agent error codes:

- `budget_missing_tenant`
- `budget_missing_agent`
- `budget_exceeded_tenant_daily`
- `budget_exceeded_tenant_monthly`
- `budget_exceeded_agent_daily`
- `budget_exceeded_agent_monthly`

### curl example

```bash
curl -s -X POST http://localhost:8090/llm/complete \
  -H "Authorization: Bearer $TENANT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "agent_name": "summarizer",
    "purpose": "summarize_cell",
    "messages": [{"role":"user","content":"Summarize: the quick brown fox."}]
  }' | jq .
```

---

## `POST /llm/vision`

Run a vision (image-understanding) completion against the tenant's routed model.

### Request

```json
{
  "agent_name": "vision-reader",
  "purpose":    "vision_attachment",
  "realm":      "personal",
  "image": {
    "type":       "base64",
    "media_type": "image/png",
    "data":       "<base64-encoded bytes>"
  },
  "prompt":     "What does this diagram show?",
  "max_tokens": 1024,
  "model":      "claude-sonnet-4-6"
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `agent_name` | string | yes | Existing agent name in the calling tenant; required for budget enforcement and audit attribution |
| `purpose` | string | yes | Routing key |
| `realm` | string | no | Informational label |
| `image.type` | string | yes | Always `"base64"` in Slice 1 |
| `image.media_type` | string | yes | MIME type, e.g. `image/png`, `image/jpeg`, `image/webp` |
| `image.data` | string | yes | Base64-encoded image bytes |
| `prompt` | string | yes | User prompt describing what to extract or analyse |
| `max_tokens` | integer | no | Cap on output tokens |
| `model` | string | no | Override resolved model (honoured only when `allow-override: true`) |

### Response `200 OK`

Identical shape to `/llm/complete`, same `text`, `stop_reason`, `usage`,
`provider`, `model`, `cost_micros`, `llm_call_id` fields.

### curl example

```bash
IMAGE_B64=$(base64 -w0 /path/to/screenshot.png)

curl -s -X POST http://localhost:8090/llm/vision \
  -H "Authorization: Bearer $TENANT_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"agent_name\": \"vision-reader\",
    \"purpose\": \"vision_attachment\",
    \"image\": {
      \"type\": \"base64\",
      \"media_type\": \"image/png\",
      \"data\": \"$IMAGE_B64\"
    },
    \"prompt\": \"Describe the contents of this image.\"
  }" | jq .
```

---

## `POST /llm/vision-multi`

Run a vision completion over **multiple images plus a single prompt** in one model call,
against the tenant's routed model. Mirrors `/llm/vision` but takes an `images` array instead
of a single `image`. The gateway carries no domain logic — all N images and the prompt are
forwarded to the provider as one user message (N native image blocks + one text block).

### Request

```json
{
  "agent_name": "vision-reader",
  "purpose":    "vision_attachment",
  "realm":      "personal",
  "images": [
    { "type": "base64", "media_type": "image/png",  "data": "<base64-encoded bytes>" },
    { "type": "base64", "media_type": "image/jpeg", "data": "<base64-encoded bytes>" }
  ],
  "prompt":     "Compare these two pages.",
  "max_tokens": 1024,
  "model":      "claude-sonnet-4-6"
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `agent_name` | string | yes | Existing agent name in the calling tenant; required for budget enforcement and audit attribution |
| `purpose` | string | yes | Routing key |
| `realm` | string | no | Informational label |
| `images` | array | yes | Non-empty list of image objects (each `@NotNull`) |
| `images[].type` | string | no | Transport hint, e.g. `"base64"` |
| `images[].media_type` | string | yes | MIME type, e.g. `image/png`, `image/jpeg`, `image/webp` |
| `images[].data` | string | yes | Base64-encoded image bytes |
| `prompt` | string | yes | User prompt describing what to extract or analyse |
| `max_tokens` | integer | no | Cap on output tokens |
| `model` | string | no | Override resolved model (honoured only when `allow-override: true`) |

An empty `images` array or a missing `prompt` returns `400 Bad Request` (bean validation).

### Response `200 OK`

Identical shape to `/llm/complete` and `/llm/vision`: same `text`, `stop_reason`, `usage`,
`provider`, `model`, `cost_micros`, `llm_call_id` fields.

### curl example

```bash
IMG1=$(base64 -w0 /path/to/page1.png)
IMG2=$(base64 -w0 /path/to/page2.png)

curl -s -X POST http://localhost:8090/llm/vision-multi \
  -H "Authorization: Bearer $TENANT_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"agent_name\": \"vision-reader\",
    \"purpose\": \"vision_attachment\",
    \"images\": [
      { \"type\": \"base64\", \"media_type\": \"image/png\", \"data\": \"$IMG1\" },
      { \"type\": \"base64\", \"media_type\": \"image/png\", \"data\": \"$IMG2\" }
    ],
    \"prompt\": \"Compare these two pages.\"
  }" | jq .
```

---

## `GET /admin/tenants`

Returns all registered tenants.

**Auth:** admin token required.

### Response `200 OK`

```json
[
  {
    "id":         "550e8400-e29b-41d4-a716-446655440000",
    "name":       "hivemem",
    "killed":     false,
    "killReason": null
  }
]
```

---

## `POST /admin/tenants`

Create a new tenant and receive its one-time bearer token. Side-effect: a
default routing rule (`provider=anthropic`, `model=claude-sonnet-4-6`,
`priority=1000`, `realm=NULL`, `purpose=NULL`) is auto-seeded for the new
tenant.

**Auth:** admin token required.

### Request

```json
{ "name": "dracul" }
```

### Response `201 Created`

```json
{
  "id":    "550e8400-e29b-41d4-a716-446655440001",
  "name":  "dracul",
  "token": "a3f9c2d1e8b7..."
}
```

> **Important:** the plaintext `token` is returned exactly once and is never
> stored. Copy it to your secret store immediately. If lost, you must delete and
> re-create the tenant.

---

## `POST /admin/tenants/{name}/kill`

Activate the kill-switch for a tenant. All subsequent `/llm/**` calls from that
tenant return `403` until the switch is cleared or `until` elapses.

**Auth:** admin token required.

### Request

```json
{
  "reason": "runaway cost spike, pausing for investigation",
  "until":  "2026-05-06T00:00:00Z",
  "setBy":  "ops@example.com"
}
```

| Field | Required | Description |
|---|---|---|
| `reason` | yes | Human-readable explanation, stored in audit |
| `until` | no | ISO-8601 timestamp; omit to kill indefinitely |
| `setBy` | no | Free-form attribution string (defaults to `"admin"`) |

### Response `204 No Content`

---

## `GET /admin/tenants/{name}/budget`

Returns the current tenant budget policy plus live daily/monthly usage state.

**Auth:** admin token required.

### Response `200 OK`

```json
{
  "daily_cap_micros": 100000,
  "monthly_cap_micros": 2000000,
  "daily_warn_percent": 80,
  "monthly_warn_percent": 90,
  "daily_usage_micros": 320,
  "monthly_usage_micros": 1820,
  "daily_remaining_micros": 99680,
  "monthly_remaining_micros": 1998180,
  "daily_warned": false,
  "monthly_warned": false,
  "daily_blocked": false,
  "monthly_blocked": false
}
```

If no tenant budget exists yet, the cap and warn fields are `null` and usage is `0`.

## `PATCH /admin/tenants/{name}/budget`

Create or partially update a tenant budget policy.

**Auth:** admin token required.

### Request

```json
{
  "daily_cap_micros": 100000,
  "monthly_cap_micros": 2000000,
  "daily_warn_percent": 80,
  "monthly_warn_percent": 90
}
```

Fields may be omitted to leave the current value unchanged. Explicit `null` clears that field.

### Response `200 OK`

Returns the same shape as `GET /admin/tenants/{name}/budget`.

---

## `GET /admin/tenants/{tenant}/agents/{agent}/budget`

Returns the configured budget policy and live usage state for one agent inside one tenant.

**Auth:** admin token required.

## `PATCH /admin/tenants/{tenant}/agents/{agent}/budget`

Create or partially update an agent budget policy. Request and response shapes are identical to the tenant budget endpoints.

Agent budgets are operational gating, not metadata:

- direct `/llm/**` calls require `agent_name` and a live agent budget
- manual `/agents/{name}/run` triggers require an operational agent budget
- scheduler ticks, batch submission, and unpausing an agent are blocked without budget readiness

---

## `DELETE /admin/tenants/{name}/kill`

Clear an active kill-switch immediately.

**Auth:** admin token required.

### Response `204 No Content`

---

## `GET /admin/tenants/{name}/kill`

Read the current kill-switch state for a tenant.

**Auth:** admin token required.

### Response `200 OK`

```json
{
  "until":  "2026-05-06T00:00:00Z",
  "reason": "runaway cost spike, pausing for investigation",
  "setBy":  "ops@example.com"
}
```

`until` is omitted (null) when the tenant is not currently killed.

---

## Agents

Tenant-scoped CRUD on agent definitions. Tool definition shape and webhook
contract are documented in [agents.md](agents.md).

### `POST /agents`

Body:
```json
{
  "name": "bee",
  "system_prompt": "you are a bee",
  "model_purpose": "summarize_cell",
  "tools": [ /* see agents.md */ ],
  "output_schema": { "type": "object", "properties": {"finding":{"type":"string"}}, "required": ["finding"] },
  "max_turns": 10,
  "max_run_seconds": 60,
  "max_tokens": 8192,
  "webhook_token": "secret-from-tenant"
}
```

`max_tokens` is the per-turn output-token cap passed to the provider. It is
optional — when omitted (`null`), the runtime default of **8192** applies. Set
it higher for agents that emit large structured output (e.g. multi-page
extraction) or lower to bound cost; truncation before a tool call surfaces as a
`failed` run with `no_tool_use: stop_reason=max_tokens`.

Returns `201 Created` with the persisted agent (including `id`, `version`,
timestamps). Validation errors return `400` with the failing field.

### `GET /agents`, `GET /agents/{name}`

List or retrieve agents in the calling tenant.

### `PUT /agents/{name}`

Replace the agent definition. Increments `version`. Same body shape as
`POST /agents` (without `name`).

### `PATCH /agents/{name}`

Partial update, currently supports `paused: true|false` to halt or resume
new run triggers without deleting the agent.

### `DELETE /agents/{name}`

Removes the agent. Fails with `409 Conflict` if any other agent in the
tenant still references it as a subagent target.

The `schedule` field is optional on `POST /agents`, `PUT /agents/{name}`,
and `PATCH /agents/{name}`. Empty string clears the schedule. See
[agents.md](agents.md#scheduling-cron) for the expression format and
runtime semantics. The agent detail responses include `schedule` and
`last_tick_at` (the most recent scheduler-tick decision time, may be null).

---

## Runs

### `POST /agents/{name}/run`

Body:
```json
{
  "payload": { "any": "json" },
  "completion_webhook": "http://hivemem:8080/runs/done",
  "completion_webhook_token": "another-secret"
}
```

Returns `202 Accepted`:
```json
{ "run_id": "01J...", "agent_name": "bee", "agent_version": 3, "status": "queued" }
```

`409 Conflict` if the agent is paused.
`403 Forbidden` if tenant or agent budget policy is missing or exceeded.

### `POST /agents/{name}/batch`

Submit `N` agent invocations as one Anthropic Message-Batch.

**Body:**
```json
{
  "items": [
    {"custom_id": "optional", "payload": { "any": "json" }},
    {"payload": { "any": "json" }}
  ],
  "completion_webhook": "http://...",
  "completion_webhook_token": "..."
}
```

- `items`: required, ≥1, ≤10 000.
- `custom_id` per item, optional. Must match `^[a-zA-Z0-9_-]{1,64}$` and
  be unique within the batch. If omitted, Vistierie generates one.

**Response, `202 Accepted`:**
```json
{
  "run_id": "01J...",
  "agent_name": "summarize-cell",
  "agent_version": 3,
  "status": "queued",
  "items_total": 3,
  "anthropic_batch_id": "msgbatch_..."
}
```

**Errors:**
- `400`: agent has tools, missing `output_schema`, empty/oversized
  `items`, or invalid/duplicate `custom_id`.
- `403`: tenant or agent budget gate blocked submission.
- `404`: agent not found in this tenant.
- `409`: agent paused.
- `502`: upstream Anthropic batch submission failed.

The parent run row exposed via `GET /runs/{id}` includes the extra fields
`anthropic_batch_id`, `output.items_total`, `output.items_done`,
`output.items_failed`.

See [agents.md](agents.md#batched-runs) for the full agent-level model
and v1 restrictions.

### `GET /runs/{run_id}`

Returns the run detail (status, output, error, started/finished, summary,
parent run id, child status counts). Optional `?wait_seconds=N` (max 60)
turns this into a long-poll that returns as soon as the run reaches
`done`/`failed` or the timeout expires. See [agents.md](agents.md#long-poll).

### `GET /runs`

Lists the calling tenant's runs (newest first, capped).

### `GET /runs/{run_id}/events`

Returns the event timeline for the run. Useful for observability and for
walking parent → child run trees.

---

## `/admin/routing-rules`

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/admin/routing-rules` | admin | Create a rule |
| GET  | `/admin/routing-rules?tenant=&realm=&purpose=` | admin | List with optional filters |
| GET  | `/admin/routing-rules/{id}` | admin | Read one |
| PATCH | `/admin/routing-rules/{id}` | admin | Update `provider`, `model`, `priority`, `allow_override`, `locked` |
| DELETE | `/admin/routing-rules/{id}` | admin | Delete (refuses to delete the tenant's last wildcard rule) |

### Create body

```json
{
  "tenant": "hivemem", "realm": "medical", "purpose": null,
  "provider": "ollama", "model": "llama-3.1-70b",
  "priority": 10, "allow_override": false, "locked": true
}
```

### Status codes

| Code | When |
|---|---|
| 201 | Rule created |
| 200 | List or read success |
| 204 | Delete success |
| 400 | Unknown tenant, unknown provider, priority out of range, or invalid body |
| 401 | Missing or invalid admin token |
| 404 | Rule not found |
| 409 | Duplicate `(tenant, realm, purpose)` |
| 422 | Attempt to delete a tenant's last wildcard default rule |

### PATCH semantics

Only `provider`, `model`, `priority`, `allow_override`, `locked` are
mutable. `tenant`, `realm`, `purpose` are immutable, change them by
DELETE + POST.

---

## `/admin/runs`

`GET /admin/runs?tenant=&agent=&status=&from=&to=&limit=&offset=`

Cross-tenant view of agent runs. `status` may repeat
(`?status=failed&status=timeout`). `from` and `to` filter on `started_at`
in ISO-8601. `limit` defaults to 50, capped at 200.

Response:

```json
{
  "items": [{
    "id": "01HK...", "tenant": "hivemem", "agent": "bee-isolation",
    "trigger": "scheduled", "status": "done",
    "started_at": "2026-05-09T10:12:00Z",
    "finished_at": "2026-05-09T10:12:14Z", "duration_ms": 14012,
    "llm_calls_count": 3, "total_cost_micros": 12450
  }],
  "total": 412, "limit": 50, "offset": 0
}
```

---

## `/admin/llm-calls`

`GET /admin/llm-calls?tenant=&realm=&purpose=&provider=&model=&endpoint=&status=&run_id=&from=&to=&limit=&offset=`

Cross-tenant view of LLM call audit rows. Filter combinations match the
column names. `status` may repeat. `from` / `to` filter on `created_at`.

Response:

```json
{
  "items": [{
    "id": "...", "tenant": "hivemem", "run_id": null,
    "purpose": "summarize_cell", "realm": "medical",
    "provider": "ollama", "model": "llama-3.1-70b",
    "endpoint": "complete",
    "input_tokens": 250, "output_tokens": 32,
    "cache_creation_input_tokens": 0, "cache_read_input_tokens": 0,
    "cost_micros": 0, "duration_ms": 412, "status": "ok",
    "error_code": null, "created_at": "2026-05-09T10:12:14Z"
  }],
  "limit": 50, "offset": 0
}
```

---

## `/admin/cost`

`GET /admin/cost?from=&to=&granularity=&group_by=&tenant=&realm=&purpose=&provider=&model=&endpoint=&status=`

Cross-tenant cost rollup against `llm_calls`.

| Param | Default | Description |
|---|---|---|
| `from`, `to` | last 7 days | ISO-8601, filter on `created_at` |
| `granularity` | `hour` | `hour` \| `day` \| `none` |
| `group_by` | `` | comma list: any of `tenant`, `realm`, `purpose`, `provider`, `model`, `endpoint`, `status` |
| `tenant`, `realm`, `purpose`, `provider`, `model`, `endpoint` | – | exact filter |
| `status` | – | repeatable filter (`?status=ok&status=error`) |

Response:

```json
{
  "from": "...", "to": "...",
  "granularity": "hour",
  "group_by": ["tenant","model"],
  "buckets": [
    { "ts":"2026-05-09T14:00:00Z",
      "groups":[
        {"dimensions":{"tenant":"hivemem","model":"claude-sonnet-4-6"},
         "calls":42,"input_tokens":18450,"output_tokens":3120,
         "cache_creation_input_tokens":0,"cache_read_input_tokens":6200,
         "cost_micros":12450,"cost_eur":0.012450}
      ]}
  ]
}
```

Status codes: 200, 400 (bad granularity / group_by), 401, 422 (response_too_large, narrow query, max 10 000 result rows).

---

## `/admin/llm-calls/{id}` (detail)

`GET /admin/llm-calls/{id}`, extends the row shape from `GET /admin/llm-calls` (Slice 6) with the full request body and response text from `llm_call_bodies`.

If the body has been deleted by retention, `request_json` and `response_text` are `null` and `body_evicted: true`.

---

## `GET /healthz`

Liveness probe. Returns `200 OK` with body `OK` as long as the JVM is running.
No authentication required. Safe to call from load-balancer health checks.

---

## `GET /readyz`

Readiness probe. Returns `200 OK` once the database connection is verified.
Returns `503` if the DB is unreachable. No authentication required.

---

## GET /agents/{name}/sessions

Returns the 50 most recent streaming sessions for the named agent, newest first.
Tenant-scoped (authenticated via the standard `Authorization: Bearer <token>` header).

**Response 200:**
```json
[
  {
    "id": "<uuid>",
    "opened_at": "2026-06-02T09:30:00Z",
    "closes_at": "2026-06-02T18:00:00Z",
    "last_poll_at": "2026-06-02T14:33:00Z",
    "status": "open"
  }
]
```

**Response 404:** agent not found in tenant.

Status values: `open` | `closed`.

---

## Event-source webhook contract (Vistierie → consumer)

Vistierie POSTs this request to `event_source_url` each poll cycle:

```
POST {event_source_url}
Authorization: Bearer {webhook_token}
Content-Type: application/json

{
  "session_id": "<uuid>",
  "agent":      "<agent name>",
  "since":      "<ISO-8601 timestamp of last poll | null>",
  "now":        "<ISO-8601 current timestamp>"
}
```

Expected response:
```json
{ "events": [ { "<arbitrary consumer JSON>" } ] }
```

- Empty `events` (`[]`) → nothing spawned; the poll round-trip is the only cost.
- Each event object becomes the `payload` of one child run. Vistierie treats it as opaque.
- The consumer owns deduplication, throttling, and "is this worth a token" logic.
- On 5xx: Vistierie retries once after 1 s; on persistent failure it logs and skips the poll (tick survives).
- On 4xx: Vistierie logs and skips (no retry).
