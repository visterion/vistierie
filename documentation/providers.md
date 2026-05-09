# Providers

## Anthropic plugin

The built-in Anthropic provider calls the [Messages API](https://docs.anthropic.com/en/api/messages)
directly via Spring's `RestClient`. No Anthropic SDK dependency is used.

| Parameter | Value |
|---|---|
| Endpoint | `https://api.anthropic.com/v1/messages` |
| Auth header | `x-api-key: <ANTHROPIC_API_KEY>` |
| Version header | `anthropic-version: 2023-06-01` |
| Timeout | configurable via `vistierie.anthropic.timeout-seconds` (default 60 s) |

The API key is read from the `ANTHROPIC_API_KEY` environment variable (or
`vistierie.anthropic.api-key` in YAML). The base URL can be overridden via
`vistierie.anthropic.base-url` — useful for integration tests that point to a
local stub.

Both `complete` and `vision` calls go through the same `/v1/messages` endpoint.
Vision requests encode the image as an Anthropic `image` content block with
`type: "base64"`.

### Batch endpoints (Slice 4)

The Anthropic provider implements three batch endpoints that back
`POST /agents/{name}/batch`:

| Operation | HTTP |
|---|---|
| Submit | `POST /v1/messages/batches` |
| Status | `GET /v1/messages/batches/{id}` |
| Results | streamed from the batch's `results_url` (JSONL) |

**v1 restriction:** batched agents must be single-turn (no tools, no
subagents). Multi-turn batched agents are technically supported by the
Anthropic API but each round-trip can take up to 24 h, so this is
deferred until a concrete consumer needs it.

---

## OpenAI-compatible plugin (OpenAI, xAI, …)

A single class `OpenAiCompatibleProvider` covers any LLM API that speaks the
OpenAI `/v1/chat/completions` wire format. It is registered as one Spring bean
per entry under `vistierie.providers.<name>` in `application.yaml`. Currently
ships with **OpenAI** and **xAI** preconfigured.

| Parameter | Value |
|---|---|
| Endpoint | `<base-url>/chat/completions` |
| Auth header | `Authorization: Bearer <api-key>` |
| Method | `POST` (request/response only — no streaming) |
| Body shape | OpenAI Chat Completions API |

### Wire details

- **Outgoing requests** use `max_completion_tokens` (the modern OpenAI field;
  legacy `max_tokens` is accepted but deprecated for o-series).
- **System prompt** is sent as the first message with `role: "system"`. There
  is no separate `system` field as in Anthropic.
- **Tool definitions** are translated from Vistierie's Anthropic-style shape
  `{name, description, input_schema}` to OpenAI shape
  `{type: "function", function: {name, description, parameters}}`.
- **Vision** sends the image as a `data:<media-type>;base64,<payload>` URL
  inside an `image_url` content block.
- **Responses** are mapped back to Vistierie's `ProviderResponse`:
  - `finish_reason` is translated: `stop → end_turn`, `tool_calls → tool_use`,
    `length → max_tokens`, others passthrough.
  - `tool_calls` are synthesized into Anthropic-shaped `tool_use` content blocks
    so the existing `ToolUseParser` keeps working without provider-specific
    branches.
  - `usage.prompt_tokens_details.cached_tokens` is split off into
    `Usage.cacheReadInputTokens`; the remainder goes to `inputTokens`. Neither
    OpenAI nor xAI bill for cache *creation*, so `cacheCreationInputTokens` is
    always `0`.

### OpenAI configuration

| Property | Default |
|---|---|
| `vistierie.providers.openai.base-url` | `https://api.openai.com/v1` |
| `vistierie.providers.openai.api-key` | `${OPENAI_API_KEY:}` |
| `vistierie.providers.openai.timeout-seconds` | `60` |

Models priced in `PriceTable`: `gpt-4o`, `gpt-4o-mini`, `gpt-5`, `gpt-5-mini`,
`o4-mini`. Add new entries to `PriceTable.RATES` to enable additional models.

### xAI configuration

| Property | Default |
|---|---|
| `vistierie.providers.xai.base-url` | `https://api.x.ai/v1` |
| `vistierie.providers.xai.api-key` | `${XAI_API_KEY:}` |
| `vistierie.providers.xai.timeout-seconds` | `60` |

Models priced: `grok-4`, `grok-4-fast`, `grok-code-fast-1`.

### Empty api-key behavior

A provider entry with an empty or missing `api-key` is **silently skipped** at
startup — no bean is registered, and the routing layer fails-fast with
`unknown provider <name>` if anything tries to use it. This is intentional so
that local dev environments without all keys configured can still boot.

### Pricing source

OpenAI and xAI rates are transcribed from the
[BerriAI/litellm community price catalog](https://github.com/BerriAI/litellm/blob/main/model_prices_and_context_window.json),
which carries source URLs to the upstream provider pricing pages. We
intentionally do *not* fetch this JSON at runtime — manual transcription with
in-code source attribution keeps Vistierie offline-buildable and tests
deterministic. When a provider changes prices, edit `PriceTable.RATES` and
ship a release.

### Adding another OpenAI-compatible provider (Groq, DeepSeek, Ollama, …)

The class is generic — adding a new provider takes three YAML lines:

```yaml
vistierie:
  providers:
    groq:
      base-url: https://api.groq.com/openai/v1
      api-key: ${GROQ_API_KEY:}
```

The new bean is registered automatically. No Java changes required, *unless*
the provider has wire deviations (some providers ignore `temperature`, return
non-standard error shapes, etc.). For unknown territory: capture a real
response into a fixture, mirror `OpenAiCompatibleProviderTest`, and verify.

---

## Adding a new provider

1. Implement the `de.vesterion.vistierie.provider.LlmProvider` interface:

   ```java
   public interface LlmProvider {
       /** Unique identifier used in routing config (e.g. "openai"). */
       String name();

       ProviderResponse complete(ProviderRequest req);

       ProviderResponse vision(String model, int maxTokens,
                               String mediaType, String base64, String prompt);
   }
   ```

2. Annotate the implementation with `@Component` so Spring picks it up.

3. That is all. `ProviderRegistry` builds a `Map<String, LlmProvider>` keyed by
   `LlmProvider::name` at startup and routes calls to the right implementation
   based on the routing config's `provider` field.

4. Add a row to [configuration.md](configuration.md) for any new env vars or
   config properties the provider needs.

5. Add a routing entry under `routing.tenants.<name>.purposes.<purpose>` (or
   `default`) in `application.yaml` / `routing.yaml` to start using the provider.

---

## Mock-LLM mode

Set `VISTIERIE_MOCK_LLM=true` (or `vistierie.mock-llm: true` in YAML) to
replace the real Anthropic HTTP calls with a deterministic mock.

- `MockProvider` registers under `name() = "anthropic"` so it satisfies all
  routing entries that point to `provider: anthropic`.
- `AnthropicProvider` is deactivated via `@ConditionalOnProperty` when mock mode
  is on — so the real client is never instantiated and no `ANTHROPIC_API_KEY` is
  required.
- Mock responses are fixed strings with realistic token counts for cost
  accounting tests.

Use mock mode in CI pipelines and local development to avoid consuming Anthropic
API credits.
