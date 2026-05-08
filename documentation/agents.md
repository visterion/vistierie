# Agents and Runs

Slice 2 turns Vistierie from a synchronous gateway into a tenant-scoped agent
framework. Tenants register agents, trigger runs, and either long-poll for
the result or receive a completion webhook. Vistierie owns the agent loop;
the consumer owns the prompts and tool implementations.

---

## Lifecycle

1. **Register** — `POST /agents` with the agent definition (system prompt,
   tools, output schema, limits, webhook token). Vistierie validates the JSON
   schemas and, if any tool is `type: subagent`, that the named target agent
   exists in the same tenant.
2. **Trigger** — `POST /agents/{name}/run` with a `payload`. Returns
   `202 Accepted` with a `run_id`. Run executes asynchronously on a virtual
   thread.
3. **Observe** — either:
   - long-poll `GET /runs/{run_id}?wait_seconds=N` (≤ 60s), or
   - register a `completion_webhook` URL when triggering — Vistierie POSTs
     the terminal state to it with bounded retries.
4. **Done or failed** — terminal status is `done` or `failed`. The full
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
`{ "output": <any-json> }` (or any JSON — Vistierie passes it through as the
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
intermediate tool calls — only the validated structured output.

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

The same token is **not** used for completion webhooks — those use the
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
(default 0s, 5s, 30s) and gives up after the third attempt — the run row
remains the source of truth.

---

## Long-poll

```bash
curl -H "Authorization: Bearer $TOK" \
  "http://vistierie:8090/runs/$RUN_ID?wait_seconds=15"
```

Vistierie holds the request until the run reaches a terminal state or the
timeout elapses, whichever comes first. Maximum `wait_seconds` is 60.
A response always reflects the **current** state — if it's still `running`
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
