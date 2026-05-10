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

## Feature Flags

| Property | Env var | Default | Description |
|----------|---------|---------|-------------|
| `vistierie.mock-llm` | `VISTIERIE_MOCK_LLM` | `false` | Replace all LLM calls with a stub (for integration testing) |
