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
