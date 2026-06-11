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

## Anthropic Provider

| Property | Env var | Default | Description |
|----------|---------|---------|-------------|
| `vistierie.anthropic.api-key` | `ANTHROPIC_API_KEY` | — | Required to enable the `anthropic` provider |
| `vistierie.anthropic.base-url` | — | `https://api.anthropic.com` | Override for proxies |
| `vistierie.anthropic.timeout-seconds` | — | `60` | HTTP timeout |

---

## OpenAI-compatible Providers

Defined under `vistierie.providers.<name>`:

| Sub-property | Description |
|-------------|-------------|
| `base-url` | Required. Base URL for the OpenAI-compatible endpoint |
| `api-key` | Required. Bearer token. Provider is skipped if blank |
| `timeout-seconds` | Optional. HTTP timeout, default 60 |

---

## Amazon Bedrock Provider

| Property | Env var | Default | Description |
|----------|---------|---------|-------------|
| `vistierie.bedrock.enabled` | `BEDROCK_ENABLED` | `false` | Set to `true` to enable |
| `vistierie.bedrock.region` | `AWS_REGION` | SDK default | AWS region for Bedrock calls |

Credentials use the standard AWS credential chain. No API key property.

---

## Audit

| Property | Default | Description |
|----------|---------|-------------|
| `vistierie.audit.body-retention-days` | `7` | How long LLM call bodies are retained before deletion |

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
| `vistierie.agents.tool-default-timeout-seconds` | `30` | Default per-tool HTTP timeout when a tool definition omits its own |
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
