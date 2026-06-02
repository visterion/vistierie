# Agents and Runs

Slice 2 turns Vistierie from a synchronous gateway into a tenant-scoped agent
framework. Tenants register agents, trigger runs, and either long-poll for
the result or receive a completion webhook. Vistierie owns the agent loop;
the consumer owns the prompts and tool implementations.

---

## Lifecycle

1. **Register**: `POST /agents` with the agent definition (system prompt,
   tools, output schema, limits, webhook token). Vistierie validates the JSON
   schemas and, if any tool is `type: subagent`, that the named target agent
   exists in the same tenant.
2. **Trigger**: `POST /agents/{name}/run` with a `payload`. Returns
   `202 Accepted` with a `run_id`. Run executes asynchronously on a virtual
   thread.
3. **Observe**: either:
   - long-poll `GET /runs/{run_id}?wait_seconds=N` (≤ 60s), or
   - register a `completion_webhook` URL when triggering, Vistierie POSTs
     the terminal state to it with bounded retries.
4. **Done or failed**: terminal status is `done` or `failed`. The full
   `agent_snapshot` and `messages_snapshot` are preserved for replay/audit.

---

## Tool definitions

Tools are declared inside the agent definition as a JSON array. Two types:

### HTTP tool (consumer-hosted webhook)

```json
{
  "name": "cell.read",
  "description": "Read a HiveMem cell by id",
  "input_schema": {
    "type": "object",
    "properties": { "id": { "type": "string" } },
    "required": ["id"]
  },
  "webhook_url": "http://hivemem:8080/tools/cell.read",
  "webhook_timeout_seconds": 5
}
```

Vistierie POSTs `{ "run_id", "tool_name", "input" }` to `webhook_url` with
`Authorization: Bearer <agent.webhook_token>` and headers
`X-Vistierie-Run-Id`, `X-Vistierie-Tool`. The consumer responds with
`{ "output": <any-json> }` (or any JSON, Vistierie passes it through as the
`tool_result` content). 5xx responses are retried once with a 1s gap; any
remaining error fails the run.

### Subagent tool

```json
{
  "name": "dispatch_bee",
  "description": "Spin up a Bee subagent",
  "input_schema": { "type": "object" },
  "type": "subagent",
  "target_agent": "bee"
}
```

When the parent agent emits a `tool_use` for a subagent tool, Vistierie
recursively starts a new run of `target_agent` with the `input` as payload.
The child run gets its own `messages_snapshot`; only its terminal `output`
is returned to the parent as the `tool_result` content. This is **context
shielding**: the parent never sees the child's system prompt, turns, or
intermediate tool calls, only the validated structured output.

Subagent-eligible agents must declare an `output_schema` so the parent
receives well-typed JSON.

Recursion depth is capped (default 5) by `vistierie.agents.subagent.max-depth`.

---

## Webhook token contract

The `webhook_token` field on the agent definition is the bearer token
Vistierie presents to every HTTP tool call for that agent. Tenants generate
the token, configure it on their tool service for inbound auth, and supply
it once at registration. Vistierie stores it on the agent row and includes
it in every dispatched tool request.

The same token is **not** used for completion webhooks, those use the
per-run `completion_webhook_token` supplied at trigger time.

---

## Completion webhook payload

When a run reaches `done` or `failed`, Vistierie POSTs to the configured
`completion_webhook` URL:

```json
{
  "run_id": "01J...",
  "agent_version": 3,
  "status": "done",
  "started_at": "2026-05-08T09:00:00Z",
  "finished_at": "2026-05-08T09:00:04Z",
  "summary": "first 117 chars of last assistant text",
  "output": { "...": "schema-validated JSON" },
  "error": null
}
```

Headers: `Authorization: Bearer <completion_webhook_token>`,
`X-Vistierie-Run-Id: <run_id>`. Vistierie retries on any error with backoff
(default 0s, 5s, 30s) and gives up after the third attempt, the run row
remains the source of truth.

---

## Long-poll

```bash
curl -H "Authorization: Bearer $TOK" \
  "http://vistierie:8090/runs/$RUN_ID?wait_seconds=15"
```

Vistierie holds the request until the run reaches a terminal state or the
timeout elapses, whichever comes first. Maximum `wait_seconds` is 60.
A response always reflects the **current** state, if it's still `running`
when the timeout fires, the client should re-poll.

> **Operational note:** long-polls are in-process. A Vistierie restart
> drops all open polls; clients MUST handle a `running` response and
> re-poll instead of relying on the request to block forever.

---

## Drilling down

Each run records a stream of events: `turn_started`, `tool_dispatched`,
`tool_returned`, `tool_failed`, `subagent_spawned`, `subagent_finished`,
`turn_finished`, `error`, `webhook_sent`, `webhook_failed`.

```bash
curl -H "Authorization: Bearer $TOK" \
  "http://vistierie:8090/runs/$RUN_ID/events"
```

For parent–child trees (Queen + Bees), call the same endpoint on each
`child_run_id` extracted from the parent's events or from the
`children_summary` field on `GET /runs/{id}`.

---

## Scheduling (cron)

Agents may declare a `schedule` field in their definition. When set,
Vistierie's in-process scheduler fires the agent on the configured cadence
via the same path as a manual trigger (`AgentDispatcher.trigger(... trigger="cron")`).

### Cron format

Spring 6-field cron expressions: `sec min hour day-of-month month day-of-week`.

| Expression | Meaning |
|---|---|
| `0 0 0 * * *` | every day at 00:00:00 UTC |
| `0 */5 * * * *` | every 5 minutes, on the minute |
| `0 0 */6 * * *` | every 6 hours, on the hour |
| `* * * * * *` | every second (test-only) |

Invalid expressions are rejected at registration time with HTTP 400.

### Concurrency: skip-if-running

If a previous run for the agent is still `queued` or `running` when the
next cron boundary fires, the new tick is skipped and a `cron_skipped`
event is recorded on the open run. `agents.last_tick_at` advances either way.

### Restart behaviour

Vistierie does **not** replay missed cron boundaries after a restart.
`last_tick_at` is preserved in the DB and the next tick is computed from
the current clock. Don't rely on cron for exactly-once semantics,
treat it as "fires roughly on schedule, idempotency is the consumer's job".

### Pausing

`paused: true` removes the agent from the scheduler scan. Unpausing does
not retroactively fire missed boundaries; the next tick fires fresh.

### Kill switch

When the tenant's kill switch is active, the scheduler skips the fire
entirely (no run row created). Logged at WARN level.

---

## Batched runs

`POST /agents/{name}/batch` lets a tenant submit `N` agent invocations as one
Anthropic Message-Batch request. Each item runs asynchronously at
**50 % of the standard token cost**. Typical latency is < 1 hour, max 24 h.

### Restrictions in v1

- Agent must declare an `output_schema`. Each item's output is validated
  per-item; a schema violation fails the item but not the parent.
- Agent must NOT have tools (HTTP or subagent). Tool-using agents are
  technically supported by the Anthropic API but each tool round-trip in
  batch can take up to 24 h, so v1 restricts to single-turn agents.
- Per-batch cap: 10 000 items.

### Run topology

A batch submission creates one **parent** run with `trigger="batch"` and
`anthropic_batch_id` set, plus N **child** runs with `trigger="batch_item"`
and `parent_run_id` pointing back to the parent. The parent's `output`
aggregates `{items_total, items_done, items_failed}` once all children
have terminated.

### Polling and observation

Vistierie polls the Anthropic batch every `vistierie.agents.batch.poll-millis`
(default 60 s). When the batch is `ended`, results stream into the
per-child runs and the parent reaches `done`.

Long-poll on the parent: `GET /runs/{id}?wait_seconds=N` (≤ 60 s) blocks
until the parent terminates. Or set `completion_webhook` on the request
body for a callback.

### Cost audit

Every per-item LLM call writes one `vistierie.llm_calls` row with
`batch_id` set. Cost rollups can split batch vs on-demand spend, see
[operations.md](operations.md#batch-vs-on-demand-spend).

### Example

```bash
curl -X POST http://localhost:8090/agents/summarize-cell/batch \
  -H "Authorization: Bearer $TENANT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "items": [
      {"payload": {"cell_id": "c1"}},
      {"payload": {"cell_id": "c2"}},
      {"payload": {"custom_id": "my-id-3", "cell_id": "c3"}}
    ],
    "completion_webhook": "http://hivemem:8080/runs/done"
  }'
# → 202 Accepted
# {
#   "run_id": "01J...", "agent_name": "summarize-cell",
#   "agent_version": 3, "status": "queued",
#   "items_total": 3, "anthropic_batch_id": "msgbatch_..."
# }
```

## Streaming Bee (window-bounded event-driven agent)

A **Streaming Bee** is an ordinary agent with three additional fields set. It stays active during
a time window, polls a consumer-hosted event-source webhook on a fixed cadence, and spawns one
child run per returned event. No new entity type — just nullable fields.

### Required fields

| Field | Type | Description |
|---|---|---|
| `event_source_url` | String | Consumer-hosted webhook Vistierie POSTs each poll |
| `session_duration_seconds` | Integer | Window length in seconds from the session-open cron boundary. **Non-null marks the agent as a Streaming Bee.** |
| `schedule` | String (6-field cron) | Reused as session-open trigger. Required when `session_duration_seconds` is set. |

### Optional fields

| Field | Type | Default | Description |
|---|---|---|---|
| `poll_interval_seconds` | Integer | 60 | Poll cadence within the window (rounded up to scheduler tick ~30 s) |
| `completion_webhook` / `completion_webhook_token` | String | — | Fired per child run on completion |
| `webhook_token` | String (required) | — | Bearer token for **both** the event-source POST and tool webhooks |

### Validation

If `session_duration_seconds` is set:
- `event_source_url` must be non-blank
- `schedule` must be non-blank and valid
- `session_duration_seconds` must be > 0

### Session lifecycle

Managed by `StreamingSessionCoordinator`, called from each `AgentScheduler.tick()`:

1. **OPEN** — cron boundary reached + no open session → insert `streaming_sessions` row
   (`status='open'`, `closes_at = now + session_duration_seconds`).
2. **POLL** — open session + `now - last_poll_at >= poll_interval_seconds` + `now < closes_at`
   → kill-check → POST event-source → per event: budget-check + spawn child run.
3. **CLOSE** — `now >= closes_at` → `status='closed'`.
4. **Restart/resume** — on app restart, open sessions with `closes_at` in the future resume polling automatically on the next tick.

Streaming agents are **skipped from the ordinary cron-run path** — they never produce `trigger=cron` runs.

### Child runs

Each event spawns a run with:
- `trigger = "session_event"`
- `payload = <event object>` (opaque consumer JSON)
- `session_id` — nullable UUID linking to the `streaming_sessions` row
- Full pipeline reuse: agent snapshot, system prompt, tools, output-schema validation, tier routing, completion webhook

### Cost / kill / budget

The event-source poll makes no LLM calls — idle polling is nearly free.
Before each child-run spawn, the existing kill-switch and budget enforcer are checked.
Each child run is accounted normally in `llm_calls`.

### Observability

- `GET /agents/{name}/sessions` — most recent 50 sessions (tenant-scoped)
- Child runs: `GET /runs/{id}` and `GET /runs/{id}/events` (existing endpoints)
