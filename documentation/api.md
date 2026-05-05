# Vistierie REST API

## Overview

Vistierie is a tenant-scoped, audited, kill-switchable LLM gateway. Every call
is authenticated with a bearer token, resolved against a routing policy, forwarded
to the configured provider (Anthropic in Slice 1), and recorded in the `llm_calls`
audit table. Slice 1 ships two synchronous endpoints: `POST /llm/complete` and
`POST /llm/vision`. Agent orchestration and run management are planned for Slice 2.

---

## Authentication

All endpoints except `/healthz` and `/readyz` require:

```
Authorization: Bearer <token>
```

- **`/llm/**`** — tenant bearer token issued via `POST /admin/tenants`.
- **`/admin/**`** — separate admin token configured via `VISTIERIE_ADMIN_TOKEN_HASH`.

Tokens are stored as bcrypt hashes. The plaintext is never persisted. The admin
token hash is set at deploy time (see [configuration.md](configuration.md)).

---

## `POST /llm/complete`

Run a chat completion against the tenant's routed model.

### Request

```json
{
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
| `purpose` | string | yes | Routing key — matched against tenant routing config |
| `realm` | string | no | Informational label; stored in audit log (no routing effect in Slice 1) |
| `system` | string | no | System prompt prepended before `messages` |
| `messages` | array | yes | List of `{role, content}` objects (OpenAI-compatible shape) |
| `max_tokens` | integer | no | Cap on output tokens; routing config may set a lower cap |
| `temperature` | number | no | Sampling temperature (0–1) |
| `model` | string | no | Override the resolved model — only honoured when `allow-override: true` in routing config |

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
| `llm_call_id` | string | Audit row ID — safe to log and reference in support requests |

### Error codes

| HTTP | Meaning |
|---|---|
| 400 | Validation error — malformed body or missing required field |
| 401 | Missing or invalid bearer token |
| 403 | Tenant kill-switch is active |
| 502 | Provider returned an HTTP ≥ 500 error |
| 504 | Provider request timed out |

### curl example

```bash
curl -s -X POST http://localhost:8090/llm/complete \
  -H "Authorization: Bearer $TENANT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
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
| `purpose` | string | yes | Routing key |
| `realm` | string | no | Informational label |
| `image.type` | string | yes | Always `"base64"` in Slice 1 |
| `image.media_type` | string | yes | MIME type, e.g. `image/png`, `image/jpeg`, `image/webp` |
| `image.data` | string | yes | Base64-encoded image bytes |
| `prompt` | string | yes | User prompt describing what to extract or analyse |
| `max_tokens` | integer | no | Cap on output tokens |
| `model` | string | no | Override resolved model (honoured only when `allow-override: true`) |

### Response `200 OK`

Identical shape to `/llm/complete` — same `text`, `stop_reason`, `usage`,
`provider`, `model`, `cost_micros`, `llm_call_id` fields.

### curl example

```bash
IMAGE_B64=$(base64 -w0 /path/to/screenshot.png)

curl -s -X POST http://localhost:8090/llm/vision \
  -H "Authorization: Bearer $TENANT_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
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

Create a new tenant and receive its one-time bearer token.

**Auth:** admin token required.

### Request

```json
{ "name": "draczl" }
```

### Response `201 Created`

```json
{
  "id":    "550e8400-e29b-41d4-a716-446655440001",
  "name":  "draczl",
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
  "reason": "runaway cost spike — pausing for investigation",
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
  "reason": "runaway cost spike — pausing for investigation",
  "setBy":  "ops@example.com"
}
```

`until` is omitted (null) when the tenant is not currently killed.

---

## `GET /healthz`

Liveness probe. Returns `200 OK` with body `OK` as long as the JVM is running.
No authentication required. Safe to call from load-balancer health checks.

---

## `GET /readyz`

Readiness probe. Returns `200 OK` once the database connection is verified.
Returns `503` if the DB is unreachable. No authentication required.
