# Configuration Reference

All properties can be set via `application.yaml` or overridden by environment variables.

---

## Database

| Property | Env var | Default |
|----------|---------|---------|
| `spring.datasource.url` | `VISTIERIE_DB_URL` | `jdbc:postgresql://localhost:5432/vistierie` |
| `spring.datasource.username` | `VISTIERIE_DB_USER` | `vistierie` |
| `spring.datasource.password` | `VISTIERIE_DB_PASSWORD` | `vistierie` |

---

## Admin

| Property | Env var | Description |
|----------|---------|-------------|
| `vistierie.admin.token-hash` | `VISTIERIE_ADMIN_TOKEN_HASH` | BCrypt hash of the admin Bearer token |

---

## Auth token cache

`AuthFilter` caches the resolved tenant identity for a presented Bearer token (keyed by a
SHA-256 hash of the token, not the raw token) to avoid re-running the O(n) BCrypt scan over
all tenants on every request. This is an in-process cache — correct only because Vistierie
is deployed single-instance (no replicas). It is bounded (10,000 entries, oldest-evicted) and
fully flushed whenever tenant auth state changes (tenant create, kill, clear-kill via
`AdminTenantController`).

| Property | Env var | Default | Description |
|----------|---------|---------|-------------|
| `vistierie.auth.cache.positive-ttl-seconds` | `VISTIERIE_AUTH_CACHE_POSITIVE_TTL_SECONDS` | `60` | How long a successfully resolved token→tenant mapping stays cached |
| `vistierie.auth.cache.negative-ttl-seconds` | `VISTIERIE_AUTH_CACHE_NEGATIVE_TTL_SECONDS` | `10` | How long an unknown/invalid token is cached as a "no match", to blunt repeated bad-token scans |

---

## Anthropic Provider

| Property | Env var | Default | Description |
|----------|---------|---------|-------------|
| `vistierie.anthropic.api-key` | `ANTHROPIC_API_KEY` | — | Required to enable the `anthropic` provider |
| `vistierie.anthropic.base-url` | — | `https://api.anthropic.com` | Override for proxies |
| `vistierie.anthropic.timeout-seconds` | — | `60` | HTTP timeout |

---

## Claude Subscription Provider

| Property | Env var | Default | Description |
|----------|---------|---------|-------------|
| `vistierie.claude-subscription.enabled` | `CLAUDE_SUBSCRIPTION_ENABLED` | `false` | Set to `true` to enable the `claude-subscription` provider |
| `vistierie.claude-subscription.base-url` | `CLAUDE_BRIDGE_URL` | `http://claude-bridge:8091` | Base URL of the `claude-bridge` sidecar |
| `vistierie.claude-subscription.timeout-seconds` | — | `300` | HTTP read timeout, in seconds |

---

## OpenAI-compatible Providers

Defined under `vistierie.providers.<name>`:

| Sub-property | Description |
|-------------|-------------|
| `base-url` | Required. Base URL for the OpenAI-compatible endpoint |
| `api-key` | Required. Bearer token. Provider is skipped if blank |
| `timeout-seconds` | Accepted in config but **not currently applied** to the HTTP client (no-op). |

---

## Amazon Bedrock Provider

| Property | Env var | Default | Description |
|----------|---------|---------|-------------|
| `vistierie.bedrock.enabled` | `BEDROCK_ENABLED` | `false` | Set to `true` to enable |
| `vistierie.bedrock.region` | `AWS_REGION` | SDK default | AWS region for Bedrock calls |
| `vistierie.bedrock.read-timeout-seconds` | — | `180` | HTTP socket (read) timeout in seconds for Bedrock Converse calls. The SDK default (~30s) is too short for long reasoning responses; tune this up for long-running calls. |

Credentials use the standard AWS credential chain. No API key property.

---

## Audit

| Property | Default | Description |
|----------|---------|-------------|
| `vistierie.audit.body-retention-days` | `30` | How long LLM call bodies (request/response payloads) are kept before the daily retention job deletes them. `0` keeps them forever. Only the bodies age out — the `llm_calls` metadata/cost row is retained regardless. |

---

## Agents & Scheduler

These tune the agent runtime, the cron scheduler, batch polling, and completion
webhooks. All are optional — the defaults are production-sane. Set via
`application.yaml` or the matching relaxed-binding env var (e.g.
`VISTIERIE_AGENTS_SCHEDULER_TICK_MILLIS`).

| Property | Default | Description |
|----------|---------|-------------|
| `vistierie.agents.scheduler.tick-millis` | `30000` | How often the scheduler polls for due cron agents, in ms |
| `vistierie.agents.subagent.max-depth` | `5` | Maximum recursion depth for subagent dispatch (context-shielding guard) |
| `vistierie.agents.tool-default-timeout-seconds` | `30` | Default per-tool timeout when a tool definition omits its own (`webhook_timeout_seconds` for HTTP tools, `mcp_timeout_seconds` for MCP tools) |
| `vistierie.agents.mcp-retry-base-millis` | `1000` | Base delay, in ms, for the MCP tool retry exponential backoff (up to 3 retries: base × 1, × 2, × 4) |
| `vistierie.agents.completion-webhook.retry-base-millis` | `5000` | Base backoff between completion-webhook delivery retries, in ms |
| `vistierie.agents.batch.max-items` | `10000` | Maximum items accepted in a single batch submission |
| `vistierie.agents.batch.poll-millis` | `60000` | How often an in-flight batch's status is polled, in ms |

---

## Pricing

| Property | Default | Description |
|----------|---------|-------------|
| `vistierie.pricing.cost-multiplier` | `1.0` | Global multiplier applied to every computed `cost_micros` (e.g. an FX buffer or markup over raw provider pricing) |

---

## Budgeting

Hard budgets do not use static application properties. They are runtime data
stored in PostgreSQL and managed through the admin budget endpoints:

- `PATCH /admin/tenants/{name}/budget`
- `PATCH /admin/tenants/{tenant}/agents/{agent}/budget`

Operational rules:

- Every direct `/llm/complete` and `/llm/vision` request must include `agent_name`.
- The referenced agent must exist in the authenticated tenant.
- Both a tenant budget and an agent budget must be operational before a billable call or activation path is allowed.
- Daily and monthly caps are evaluated on persisted `llm_calls.cost_micros`.

---

## Feature Flags

| Property | Env var | Default | Description |
|----------|---------|---------|-------------|
| `vistierie.mock-llm` | `VISTIERIE_MOCK_LLM` | `false` | Replace all LLM calls with a stub (for integration testing) |
