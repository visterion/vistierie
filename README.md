# Vistierie
<img width="1584" height="672" alt="image" src="https://github.com/user-attachments/assets/c737bb3f-4298-451c-8cf3-813b62184642" />

> **A Java agent framework that lets any application become agentic, with cost-discipline and operational controls built into the core.**

Vistierie runs LLM-driven worker agents on behalf of consumer applications.
You hand it a tool schema, a system prompt, and (optionally) a cron
expression; Vistierie owns the rest: parallel HTTP tool dispatch,
recursive subagents with context shielding, scheduled execution, a
per-call audit trail with token-accurate EUR-micros cost, a kill switch
per tenant, and tier-based model routing.

[![docker](https://github.com/visterion/vistierie/actions/workflows/docker.yml/badge.svg)](https://github.com/visterion/vistierie/actions/workflows/docker.yml)
[![test](https://github.com/visterion/vistierie/actions/workflows/test.yml/badge.svg)](https://github.com/visterion/vistierie/actions/workflows/test.yml)
[![codeql](https://github.com/visterion/vistierie/actions/workflows/codeql.yml/badge.svg)](https://github.com/visterion/vistierie/actions/workflows/codeql.yml)
[![codecov](https://codecov.io/gh/visterion/vistierie/graph/badge.svg)](https://codecov.io/gh/visterion/vistierie)
[![lines of code](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/visterion/vistierie/main/badges/loc.json&cacheSeconds=300)](https://github.com/visterion/vistierie)
[![License: Apache 2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/java-25-blue)](https://openjdk.org)
[![Spring Boot](https://img.shields.io/badge/spring%20boot-4.1-6DB33F)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/postgresql-17-336791)](https://postgresql.org)

**Docker image:** [`ghcr.io/visterion/vistierie:main`](https://github.com/visterion/vistierie/pkgs/container/vistierie)

---

## What Vistierie is for

Modern applications increasingly need to do things autonomously: curate
data overnight, react to events, run periodic checks, dispatch worker
LLMs against scoped tasks. Building that yourself means stitching
together an SDK, a scheduler bean, a tool-dispatch loop, an audit
table, a cost rollup, and a way to switch it all off when something
goes wrong.

Vistierie is the service that owns that stitching. Your application
keeps its prompts, tools, and domain logic; Vistierie keeps the runtime.

**What sets it apart from LangChain4j and Spring AI:** cost-discipline
and operational controls are first-class concepts.

- **Tier-based routing**: declare an agent's purpose (`reasoning`,
  `routine`, `bulk`); the resolver picks the concrete model. Switching
  Opus to Haiku is a config change.
- **Tenant kill switch**: one POST freezes all autonomous activity
  for a tenant. Checked before every dispatch and every cron tick.
- **Per-call audit**: every LLM call writes a row to
  `vistierie.llm_calls` with input/output/cache tokens and EUR-micros
  cost. Failed calls land there too, they're the most important to
  observe.
- **Privacy-locked routing**: rules can pin a sensitive realm (e.g.
  `medical`) to a specific provider regardless of any model override
  in the request body.
- **Cost-optimized fallback routing**: routing rules carry an optional
  one-step fallback provider+model, including Claude-subscription support
  via the `claude-bridge` sidecar, so a failed or quota-limited primary
  call degrades gracefully instead of failing the request.

---

## Two consumers, two perspectives

Vistierie sees only opaque `tenant`, `realm`, `purpose`, `messages`,
`payload`. The semantics live with the consumer.

### From HiveMem's perspective

HiveMem is a knowledge base. Its data hygiene drifts: cells get added
but the **knowledge graph** doesn't always learn the new facts, and
related cells often sit orphaned without **tunnels** linking them.
HiveMem registers a **Queen** that runs hourly and two specialized
**Bees**: one extracts missing KG facts, one builds missing tunnels.
The Queen scans a realm, picks the worst-organized one, and
dispatches both Bees in parallel for that realm.

```bash
# 1: Bee that extracts knowledge-graph facts from a cell batch
curl -X POST http://vistierie:8090/agents \
  -H "Authorization: Bearer $HIVEMEM_TOKEN" -d '{
    "name": "bee-kg-extractor",
    "system_prompt": "Extract subject-predicate-object facts from the given cells. Reuse existing KG entities where possible.",
    "model_purpose": "routine",
    "tools": [
      {"name":"cell.read","webhook_url":"http://hivemem:8080/tools/cell.read",
       "input_schema":{"type":"object"}},
      {"name":"kg.add","webhook_url":"http://hivemem:8080/tools/kg.add",
       "input_schema":{"type":"object"}}
    ],
    "output_schema": {"type":"object","required":["facts_added","cells_scanned"],
      "properties":{"facts_added":{"type":"integer"},"cells_scanned":{"type":"integer"}}},
    "webhook_token": "<hivemem-side-secret>"
  }'

# 2: Bee that builds tunnels between related cells in a realm
curl -X POST http://vistierie:8090/agents \
  -H "Authorization: Bearer $HIVEMEM_TOKEN" -d '{
    "name": "bee-tunnel-builder",
    "system_prompt": "Find cells that should be linked by tunnels. Score similarity, link top matches.",
    "model_purpose": "routine",
    "tools": [
      {"name":"cell.search","webhook_url":"http://hivemem:8080/tools/cell.search",
       "input_schema":{"type":"object"}},
      {"name":"tunnel.add","webhook_url":"http://hivemem:8080/tools/tunnel.add",
       "input_schema":{"type":"object"}}
    ],
    "output_schema": {"type":"object","required":["tunnels_added"],
      "properties":{"tunnels_added":{"type":"integer"}}},
    "webhook_token": "<hivemem-side-secret>"
  }'

# 3: Queen on hourly schedule; both Bees wired in as subagent tools
curl -X POST http://vistierie:8090/agents \
  -H "Authorization: Bearer $HIVEMEM_TOKEN" -d '{
    "name": "queen-curation",
    "system_prompt": "Each hour, audit one realm. If KG facts are sparse, dispatch bee-kg-extractor. If tunnels are missing, dispatch bee-tunnel-builder. You may run both in parallel.",
    "model_purpose": "reasoning",
    "schedule": "0 0 * * * *",
    "tools": [
      {"name":"realm.health","webhook_url":"http://hivemem:8080/tools/realm.health",
       "input_schema":{"type":"object"}},
      {"name":"extract_kg_facts","type":"subagent","target_agent":"bee-kg-extractor",
       "input_schema":{"type":"object","required":["realm","cell_ids"]}},
      {"name":"build_tunnels","type":"subagent","target_agent":"bee-tunnel-builder",
       "input_schema":{"type":"object","required":["realm"]}}
    ],
    "output_schema": {"type":"object","required":["realm","actions"],
      "properties":{"realm":{"type":"string"},"actions":{"type":"array"}}},
    "webhook_token": "<hivemem-side-secret>"
  }'
```

Every hour Vistierie fires the Queen. The Queen calls
`realm.health` to find the realm in worst shape, then emits both
subagent tool-uses in the same turn, Vistierie dispatches the two
Bees on virtual threads in parallel. Each Bee runs its own loop with
its own tools (`cell.read`/`kg.add` vs `cell.search`/`tunnel.add`)
and returns a validated JSON object: `{facts_added: 47,
cells_scanned: 120}` and `{tunnels_added: 9}`. Those two
`tool_result` blocks, and nothing else from the Bee transcripts,
land in the Queen's context (see [Context shielding](#context-shielding)
below). The Queen aggregates them into its `actions` array, hits
`end_turn`, and HiveMem receives the verdict via completion webhook.

### From Dracul's perspective

Dracul runs nightly and needs to dispatch **Strigoi** agents that
hunt for findings across its data. Different Strigoi types want
different model tiers, `Strigoi-Spin` reasons hard and gets Sonnet,
`Strigoi-Echo` is a cheap classifier and gets Haiku.

```bash
# Dracul registers a Strigoi, purpose drives tier-based routing
curl -X POST http://vistierie:8090/agents \
  -H "Authorization: Bearer $DRACUL_TOKEN" -d '{
    "name": "strigoi-spin",
    "system_prompt": "You investigate anomalies and report findings.",
    "model_purpose": "reasoning",
    "schedule": "0 0 3 * * *",
    "tools": [
      {"name":"prey.scan","webhook_url":"http://dracul:8081/tools/prey.scan",
       "input_schema":{"type":"object"}}
    ],
    "output_schema": {"type":"object","required":["findings"],
      "properties":{"findings":{"type":"array"}}},
    "webhook_token": "<dracul-side-secret>"
  }'
```

At 03:00 every night, Vistierie wakes the Strigoi, routes it to the
provider+model that the operator wired up for `dracul/reasoning`, and
delivers the validated `findings` array back to Dracul via webhook.
If the operator flips the kill switch on the `dracul` tenant, no
Strigoi fires the next night until the switch is released.

---

## Context shielding

The single non-trivial idea in Vistierie. When a parent agent
dispatches a subagent, the parent never sees the child's system
prompt, intermediate turns, or tool calls. Only the **validated JSON
output** crosses the boundary, packaged as a `tool_result` block.

```mermaid
flowchart LR
    subgraph Parent["Parent run"]
        Pmsg["messages_snapshot<br/>(visible to parent)"]
        Pres["[tool_result]<br/>{verdict: …}"]
    end

    subgraph Child["Subagent run (shielded)"]
        Csys[child system prompt]
        Cmsg[child transcript:<br/>tools, turns, internals]
        Cout[validated output]
    end

    Pmsg ==>|"tool_use input"| Csys
    Csys --> Cmsg --> Cout
    Cout ==>|"only validated output<br/>crosses the boundary"| Pres

    style Csys fill:#fde
    style Cmsg fill:#fde
    style Pres fill:#dfe
```

**Why it matters.** A Queen orchestrating five Bees doesn't pay for
five full Bee transcripts in its own context window. A Bee operating
on `medical` cells doesn't leak raw cell content into a Queen running
with broader scope. Every subagent-eligible agent declares an
`output_schema`; validation happens before the boundary crosses, so
the parent always receives well-typed JSON.

---

## How runs start

Every run shares one execution path; only the trigger differs.

- **Manual**: `POST /agents/{name}/run` returns 202 with a `run_id`.
  Long-poll with `GET /runs/{id}?wait_seconds=30` for the result.
- **Subagent**: a parent agent emits `tool_use` with `type=subagent`.
  Recursion is bounded (default depth 5).
- **Cron**: agents with a `schedule` field fire on the next boundary.
  A 30-second tick is kill-switch-aware and skips if the previous run
  is still open. Idempotency is the consumer's job.
- **Streaming**: an agent with `session_duration_seconds` set becomes a
  **Streaming Bee**. On its `schedule` boundary it opens a time-boxed
  session, polls a consumer-hosted `event_source_url` every
  `poll_interval_seconds`, and spawns one child run
  (`trigger=session_event`) per returned event until the window closes.
  Idle polling makes no LLM calls; inspect sessions with
  `GET /agents/{name}/sessions`.

For tasks that tolerate < 1 h latency, `POST /agents/{name}/batch`
routes through Anthropic's Message Batches API at 50 % cost (up to
10 000 items per batch). Batch mode requires the Anthropic provider;
Bedrock and other providers are not supported for batch runs.

---

## Inspect & search runs

Every completed run is captured as a provider-neutral transcript
(`GET /runs/{id}/transcript?view=digest|compact|full`) with per-tool-call
drill-down via `GET /runs/{id}/tool-calls/{toolUseId}`, and indexed into a
Postgres full-text document. Search a tenant's runs with
`GET /runs/search?q=...` (filters: `agent`, `status`, `has_error`, `from`,
`to`); operators search any tenant via `GET /admin/runs/search?tenant=...`.

List a tenant's runs newest-first with `GET /runs` — a bare JSON array,
paged via `limit` (default 100, max 200) and `offset`, optionally narrowed
to an ISO-8601 window with `from` (inclusive) and `to` (exclusive).

---

## Synchronous LLM gateway

Not everything needs an agent. For a one-shot request/response call,
hit the gateway directly, the same tier routing, per-call audit, EUR-micros
cost accounting, and tenant kill switch all still apply:

- `POST /llm/complete`: text completion against the tenant's routed model.
- `POST /llm/vision`: single-image understanding (one `image` + a `prompt`).
- `POST /llm/vision-multi`: N images plus one prompt forwarded as a single
  model call (N native image blocks + one text block).

Each response carries the same `text`, `stop_reason`, `usage`,
`cost_micros`, and `llm_call_id` fields and writes a `vistierie.llm_calls`
row, just like an agent run. Vision requests route through whichever
provider the operator wired up for the call's `<tenant, realm, purpose>`.

---

## Quick start

Ten minutes from a clean machine to an audited LLM call — with no provider
credentials and no cost. Every command and response below is from an end-to-end
run against `ghcr.io/visterion/vistierie:main`.

**Prerequisites:** Docker with the Compose plugin. Nothing else — no JDK, no
`htpasswd`, no Postgres.

### 1. Clone and copy the env template

```bash
git clone https://github.com/visterion/vistierie.git
cd vistierie
cp .env.example .env
```

### 2. Generate the admin token hash

Vistierie stores only the bcrypt hash of the admin bearer token. A throwaway
container does the hashing, so nothing has to be installed:

```bash
ADMIN_TOKEN=demo-admin-token
docker run --rm httpd:2.4-alpine htpasswd -bnBC 12 "" "$ADMIN_TOKEN" | tr -d ':\n'
```

```
$2y$12$sEMQHI3H/GU8qRUjwAhcRu6rDnLlEKC1j.JG3UvmD1Wk/xZSsZkya
```

(The output has no trailing newline, so your prompt lands on the same line.)

> [!IMPORTANT]
> **Single-quote the hash in `.env`.** Compose interpolates `$name` sequences
> in `.env` values, so an unquoted hash is silently truncated — compose only
> warns `The "…" variable is not set. Defaulting to a blank string.` and every
> `/admin/` call then fails with 401. Quote generated passwords too.
>
> ```dotenv
> VISTIERIE_DB_PASSWORD='<generated-password>'
> VISTIERIE_ADMIN_TOKEN_HASH='$2y$12$sEMQHI3H/GU8qRUjwAhcRu6rDnLlEKC1j.JG3UvmD1Wk/xZSsZkya'
> ```

### 3. Run without any provider credentials

Set this in `.env` for the first run — `MockProvider` then serves deterministic
canned responses, makes no outbound calls, and registers under the provider name
`anthropic`, so routing rules are identical to a real deployment:

```dotenv
VISTIERIE_MOCK_LLM=true
```

### 4. Start the stack

```bash
docker compose up -d
```

```
 Container vistierie-db  Healthy
 Container vistierie     Started
```

Wait for readiness (about 10 s cold start, Flyway migrations included):

```bash
curl -s http://localhost:8090/actuator/health
```

```json
{"groups":["liveness","readiness"],"status":"UP"}
```

### 5. Create a tenant

```bash
curl -s -X POST http://localhost:8090/admin/tenants \
  -H "Authorization: Bearer demo-admin-token" \
  -H "Content-Type: application/json" \
  -d '{"name":"demo"}'
```

```json
{"id":"53b565a4-acac-4b7a-a94b-d4564ef85f99","name":"demo","token":"918c5bbd69bc8396210a0a910112805eae17ad3ede151c99"}
```

**Capture the `token` now — it is returned once and cannot be recovered.**
Tenant creation also auto-seeds a wildcard routing rule
(`anthropic` / `claude-sonnet-4-6`), so no routing rule is needed for the first
call. Export your own token for the next steps:

```bash
export T=<the token from the response above>
```

### 6. Optional: pin a model for one purpose

Skip this and the seeded wildcard rule applies. The run below adds an explicit
rule, which is why the responses further down report `claude-haiku-4-5` rather
than the seeded `claude-sonnet-4-6`:

```bash
curl -s -X POST http://localhost:8090/admin/routing-rules \
  -H "Authorization: Bearer demo-admin-token" \
  -H "Content-Type: application/json" \
  -d '{"tenant":"demo","realm":null,"purpose":"free_pick",
       "provider":"anthropic","model":"claude-haiku-4-5",
       "priority":200,"allow_override":false,"locked":false}'
```

```json
{"id":"65e8f3cf-62d3-4e01-97eb-14ac74393887","realm":null,"purpose":"free_pick",
 "provider":"anthropic","model":"claude-haiku-4-5","priority":200,
 "allow_override":false,"locked":false}
```

### 7. Create an agent

`/llm/complete` requires an existing `agent_name`, so an agent is a prerequisite
even for the synchronous gateway. `tools` and `webhook_token` are mandatory —
for a tool-less agent pass an empty list and a placeholder token:

```bash
curl -s -X POST http://localhost:8090/agents \
  -H "Authorization: Bearer $T" -H "Content-Type: application/json" \
  -d '{"name":"demo-agent","system_prompt":"You are a helpful assistant.",
       "model_purpose":"free_pick","tools":[],"webhook_token":"unused-but-required"}'
```

```json
{"id":"b3a00beb-29c2-4263-9a7a-695f866c9590","name":"demo-agent",
 "model_purpose":"free_pick","tools":[],"max_turns":25,"paused":false,"version":1}
```

### 8. Set the tenant and agent budgets

Both must exist, otherwise the call is rejected with
`403 budget_missing_tenant`:

```bash
curl -s -X PATCH http://localhost:8090/admin/tenants/demo/budget \
  -H "Authorization: Bearer demo-admin-token" -H "Content-Type: application/json" \
  -d '{"daily_cap_micros":1000000,"monthly_cap_micros":10000000}'

curl -s -X PATCH http://localhost:8090/admin/tenants/demo/agents/demo-agent/budget \
  -H "Authorization: Bearer demo-admin-token" -H "Content-Type: application/json" \
  -d '{"daily_cap_micros":500000,"monthly_cap_micros":5000000}'
```

```json
{"daily_cap_micros":1000000,"monthly_cap_micros":10000000,
 "daily_usage_micros":0,"monthly_usage_micros":0,
 "daily_remaining_micros":1000000,"monthly_remaining_micros":10000000,
 "daily_blocked":false,"monthly_blocked":false}
```

### 9. Make the call

```bash
curl -s -X POST http://localhost:8090/llm/complete \
  -H "Authorization: Bearer $T" -H "Content-Type: application/json" \
  -d '{"agent_name":"demo-agent","purpose":"free_pick","messages":[{"role":"user","content":"ping"}]}'
```

```json
{"text":"[mock] ping","stop_reason":"end_turn",
 "usage":{"inputTokens":42,"outputTokens":7,"cacheCreationInputTokens":0,"cacheReadInputTokens":0},
 "provider":"anthropic","model":"claude-haiku-4-5","cost_micros":71,
 "llm_call_id":"718E796DD30949A69681BB4FC66DC78C"}
```

### 10. Prove it was audited

`/llm/complete` is the synchronous gateway and creates **no run row** — `/runs`
stays empty. The audit trail is what proves the call:

```bash
curl -s "http://localhost:8090/admin/llm-calls?tenant=demo&limit=5" \
  -H "Authorization: Bearer demo-admin-token"
```

```json
{"limit":5,"offset":0,"items":[
  {"id":"718E796DD30949A69681BB4FC66DC78C","tenant":"demo","run_id":null,
   "purpose":"free_pick","realm":null,"provider":"anthropic","model":"claude-haiku-4-5",
   "endpoint":"complete","input_tokens":42,"output_tokens":7,
   "cost_micros":71,"duration_ms":8,"status":"ok","error_code":null,
   "created_at":"2026-07-25T15:26:13.244105Z"}]}
```

The `id` is the `llm_call_id` from step 9, and the tenant budget's
`daily_usage_micros` has moved to `71` — the same figure the response reported.

### Switching to a real provider

Put the key in `.env` (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY` or `XAI_API_KEY`),
set `VISTIERIE_MOCK_LLM=false`, and restart with `docker compose up -d`. The
seeded wildcard rule already points at `anthropic` / `claude-sonnet-4-6`; change
provider or model per `<tenant, realm, purpose>` with a routing rule — see
[routing.md](documentation/routing.md).

---

### Alternative: AWS Bedrock

Instead of (or alongside) direct provider APIs:

```bash
docker run --rm -p 8090:8090 \
  -e VISTIERIE_DB_URL=jdbc:postgresql://host.docker.internal:5432/vistierie \
  -e VISTIERIE_DB_USER=vistierie \
  -e VISTIERIE_DB_PASSWORD=vistierie \
  -e VISTIERIE_ADMIN_TOKEN_HASH='<bcrypt-hash>' \
  -e BEDROCK_ENABLED=true \
  -e AWS_REGION=eu-north-1 \
  -e AWS_BEARER_TOKEN_BEDROCK='ABSK...' \
  ghcr.io/visterion/vistierie:main
```

After startup, point a routing rule at `"provider": "bedrock"` with an inference
profile ID such as `eu.anthropic.claude-sonnet-4-6`. The SDK reads
`AWS_BEARER_TOKEN_BEDROCK` natively for ABSK API key authentication.

Long Bedrock calls that exceed the default 180s socket read timeout can be tuned
via `vistierie.bedrock.read-timeout-seconds` (see configuration.md).

### Alternative: Claude Max subscription

To bill against a **Claude Max subscription** rather than a metered API key, run
the [`claude-bridge`](claude-bridge/) sidecar and point Vistierie at it:

```bash
docker run --rm -p 8090:8090 \
  -e VISTIERIE_DB_URL=jdbc:postgresql://host.docker.internal:5432/vistierie \
  -e VISTIERIE_DB_USER=vistierie \
  -e VISTIERIE_DB_PASSWORD=vistierie \
  -e VISTIERIE_ADMIN_TOKEN_HASH='<bcrypt-hash>' \
  -e CLAUDE_SUBSCRIPTION_ENABLED=true \
  -e CLAUDE_BRIDGE_URL='http://claude-bridge:8091' \
  ghcr.io/visterion/vistierie:main
```

Then target a routing rule at `"provider": "claude-subscription"` with
`"anthropic"` as its fallback, so subscription-quota exhaustion (429) or a bridge
failure (502) degrades to the metered API-key provider. Batch runs always stay on
`anthropic`.

For local development:

```bash
cd java-server && docker compose -f docker-compose.dev.yml up --build
```

Seeding tenants, generating the admin bcrypt hash, and cost-rollup
queries: [`documentation/operations.md`](documentation/operations.md).

---

## Documentation

**Start here, by what you are doing:**

| I want to… | Read |
|---|---|
| Run Vistierie for my own services | [operations.md](documentation/operations.md) — tenants, backups, kill switch, cost queries · [configuration.md](documentation/configuration.md) — every property and env var |
| Call it from my application | [api.md](documentation/api.md) — REST reference · [routing.md](documentation/routing.md) — how `<tenant, realm, purpose>` picks a provider |
| Build agents on it | [agents.md](documentation/agents.md) — agent definition, tools, subagent context shielding, scheduling |
| Add a provider or change internals | [architecture.md](documentation/architecture.md) — system overview, data model, request flow · [providers.md](documentation/providers.md) — provider plugins and how to add one · [CONTRIBUTING.md](CONTRIBUTING.md) |
| Judge whether it fits at all | [Project values](#project-values) below — including what Vistierie deliberately is *not* · [SECURITY.md](SECURITY.md) — trust boundary |

---

## Build from source

Requires JDK 25 and Docker (for the Postgres testcontainer used in tests).

```bash
export JAVA_HOME=/path/to/jdk-25
cd java-server
./mvnw test                        # full suite
./mvnw -Pstress test               # opt-in concurrency stress
./mvnw -DskipTests package
java -jar target/vistierie-1.2.0.jar
```

---

## Project values

- **The two-consumer rule.** A feature belongs in Vistierie only if
  both HiveMem and Dracul benefit from it. Single-consumer features
  stay in the consumer.
- **Slim consumers.** Prompts, tool implementations, and domain logic
  live in HiveMem / Dracul. Vistierie sees opaque `tenant`, `realm`,
  `purpose`, `messages`, `payload`, nothing else.
- **Audit before features.** Every LLM call writes a row regardless
  of whether the call succeeded, failed calls are the most
  important to observe.
- **Not an MCP server, not a workflow engine, not a multi-agent bus,
  not a prompt library, not a vector store.** Reasoning lives with
  the consumer; Vistierie owns the runtime.

---

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE).
