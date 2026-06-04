# Providers

Vistierie routes LLM calls to one provider per request, selected via routing rules.
Each provider is identified by a string name (e.g. `"anthropic"`, `"openai"`, `"bedrock"`).

---

## Anthropic

**Name:** `anthropic`

Direct integration with the Anthropic Messages API. Supports text completion, vision,
multi-image vision (`/llm/vision-multi`), tool use, and batch processing.

**Configuration:**

| Property | Env var | Default | Required |
|----------|---------|---------|----------|
| `vistierie.anthropic.api-key` | `ANTHROPIC_API_KEY` | — | yes |
| `vistierie.anthropic.base-url` | — | `https://api.anthropic.com` | no |
| `vistierie.anthropic.timeout-seconds` | — | `60` | no |

**Supported model IDs:** Any Anthropic model string, e.g. `claude-sonnet-4-6`.

---

## OpenAI-compatible

**Names:** `openai`, `xai`, or any name defined under `vistierie.providers.*`

Generic adapter for any API that speaks the OpenAI `/v1/chat/completions` wire format.
Supports text completion, vision, multi-image vision (`/llm/vision-multi`), and tool use.
No batch support.

**Configuration** (one block per provider):

```yaml
vistierie:
  providers:
    openai:
      base-url: https://api.openai.com/v1
      api-key: ${OPENAI_API_KEY:}
      timeout-seconds: 60
    xai:
      base-url: https://api.x.ai/v1
      api-key: ${XAI_API_KEY:}
```

Providers with an empty `api-key` are silently skipped.

---

## Amazon Bedrock

**Name:** `bedrock`

Routes calls to Amazon Bedrock via the Converse API. Supports all models available
in Bedrock: Anthropic Claude, Amazon Nova, Titan, Mistral, and others.
Supports text completion, vision, multi-image vision (`/llm/vision-multi`), and tool use.
No batch support.

> **Multi-image limit:** Bedrock's Converse API caps a single request at roughly 20 images.
> Vistierie does not enforce a hard cap — requests above the provider limit are rejected by
> Bedrock and surface as a `ProviderException` (same handling as `/llm/vision`). Callers that
> need more images batch them into multiple `/llm/vision-multi` requests.

**Authentication:** Standard AWS credential chain — environment variables
(`AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` / `AWS_SESSION_TOKEN`), shared
credentials file (`~/.aws/credentials`), EC2 instance profile, or ECS task role.
No `api-key` property is used.

**Configuration:**

| Property | Env var | Default | Required |
|----------|---------|---------|----------|
| `vistierie.bedrock.enabled` | `BEDROCK_ENABLED` | `false` | yes (to enable) |
| `vistierie.bedrock.region` | `AWS_REGION` | SDK default | no |

```yaml
vistierie:
  bedrock:
    enabled: ${BEDROCK_ENABLED:false}
    region: ${AWS_REGION:}
```

**Supported model IDs:** Bedrock model ARNs/IDs, e.g.:
- `anthropic.claude-3-5-sonnet-20241022-v2:0`
- `amazon.nova-pro-v1:0`
- `amazon.titan-text-premier-v1:0`
- `mistral.mistral-large-2402-v1:0`
