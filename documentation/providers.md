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

## Claude Subscription (via claude-bridge)

**Name:** `claude-subscription`

Talks to the `claude-bridge` sidecar over plain HTTP (`POST /v1/complete`), which in
turn calls the Claude Agent SDK authenticated with a Claude Max subscription token
instead of a per-token API key. Supports text completion, vision, multi-image
vision (`/llm/vision-multi`), and tool use. **No batch support** — batch traffic
always stays on the `anthropic` (API-key) provider.

**Tool use (bridge sessions):** when `ProviderRequest.tools()` is non-empty, the
provider forwards a `tools` array on `/v1/complete` containing only the wire-safe
keys (`name`, `description`, `input_schema`) — Vistierie's internal `ToolDef` keys
(`type`, `webhook_url`, `target_agent`, ...) are stripped and never sent to the
bridge. When `ProviderRequest.toolChoice()` is also set, it is forwarded verbatim as
`tool_choice` on the same request; it is only ever sent alongside a non-empty `tools`
array. The Agent SDK behind the bridge cannot force a tool choice by itself — for the
narrow shape of request where that matters, the bridge substitutes native structured
output instead (see "Forced tool calls" below); every other shape keeps the ordinary
agentic path, where `tool_choice` is not carried through at all and is simply
ignored. The bridge may
respond with `stop_reason: "tool_use"`, a Claude-style
`content_blocks` array (including `{type: "tool_use", id, name, input}` entries),
and a `session_id`. Vistierie surfaces both on `ProviderResponse` (`contentBlocks`,
`sessionId`). `session_id` is transport-internal to the bridge conversation: to
continue a tool-use turn, the caller passes it back via
`ProviderRequest.metadata().get("provider_session_id")`, which the provider
forwards as `session_id` on the next `/v1/complete` call. This requires a current
`claude-bridge` image with tool-session support — an older bridge ignores the
`tools`/`session_id` fields.

**How the bridge advertises `input_schema`:** the Claude Agent SDK takes tools as
typed parameters, not as raw JSON Schema, so the bridge derives them. The derivation
covers objects, nested properties, arrays and their item types, scalars, `enum`
members and `integer`. All properties are advertised as optional, `required` is
ignored, and unknown constructs (`anyOf`, `oneOf`, `allOf`, `$ref`, `const`) degrade
to an unconstrained type. The full JSON Schema is additionally appended to the tool
description, so the model also sees the constraints the derived types cannot express.

The derived types **inform the model but never gate a call**: every top-level
property carries `.catch(undefined)`, so validating tool input against them cannot
fail. That is deliberate. Arguments are forwarded verbatim from the assistant block,
so SDK-side validation protects nothing — while an SDK-side rejection lands *after*
Vistierie has already received and dispatched the `tool_use` block, desynchronising
the bridge's FIFO call matcher and making the model retry, i.e. executing the tool
twice. One tool is the exception: `submit_result`, the agent runner's own output
channel, has its `input_schema` set to the agent's `output_schema`
(`ResultToolFactory.build`), and the runner validates that call's arguments against
it (`AgentRunner`, `schemas.validate(outputSchemaNode, submitBlock.input())`) — a
violation is fed back as a `tool_result` for the model to correct, up to a bounded
number of retries, before the run is failed. Every other tool's arguments reach
`ToolDispatcher` unvalidated, and the forced-single-tool route below validates
nothing either (see "Forced tool calls").

Both halves are load-bearing, and the bridge's tests pin both. Structure is what a
model cannot reliably guess: with an untyped schema it may deliver a nested array as
a JSON-encoded string — an observed production failure, not a hypothetical. So the
advertised schema must keep its constraints, and parsing must never reject.

**Forced tool calls (structured output):** the Claude Agent SDK behind this provider
has no way to force a tool choice — left to itself it can still answer in prose even
when `tool_choice` asks for one. The bridge takes this route only for the narrow
shape of request where forcing a single tool is unambiguous: `tool_choice` is
`{"type": "tool", "name": N}`, the request's `tools` array holds **exactly one**
entry, its `name` matches, and its `input_schema` is a real object (not a string,
not an array, not missing). A multi-tool request that happens to force one of its
tools is the agentic shape, not this one — offering only that one tool would starve
the model of the others it needs — so it is deliberately excluded and keeps the
ordinary agentic path, as is a stringified or otherwise non-object schema. When the
narrow shape matches, the bridge serves the request through the SDK's native
structured output, constraining the reply to that one tool's schema. The bridge
itself only checks that a payload came back at all (see below); it hands the
schema-constrained `tool_use` block back to the caller unvalidated, so callers parse
it exactly as they parse the other providers' tool calls and are responsible for
validating it against the schema themselves. The reply is a single, complete turn:
it carries no `session_id`, so there is nothing to continue — forcing a tool is a
one-shot request, not an agent loop. It also reports real token usage, unlike the
agentic tool path's `tool_use` responses, which report zero usage because the run is
still in progress. Any request outside the narrow shape above — no `tool_choice`,
another choice type, more than one tool, an unknown tool name, a non-object
`input_schema`, or a request continuing a session (`session_id` set) — keeps the
ordinary agentic tool path instead. If the SDK returns no structured payload, the
bridge answers `502 structured_output_missing`, so a routing rule's fallback
provider takes over if configured (see "Error semantics" below).

On `/v1/complete` the bridge request accepts an optional `effort` field
(`off`, `low`, `medium`, `high`, `max`), forwarded only for text completion
— never for vision. `off` disables extended thinking (Agent SDK
`thinking: {type: "disabled"}`); the other values map to Agent SDK effort
levels. Without the field the SDK default applies (thinking enabled).
Routing rules set `effort` per tenant/realm/purpose — see
[routing.md](routing.md#reasoning-effort).

The bridge also enforces `max_tokens` on the per-call SDK process via the
`CLAUDE_CODE_MAX_OUTPUT_TOKENS` environment variable. Note the interaction
with thinking: without `effort` set, extended thinking stays enabled and
thinking tokens count against the `max_tokens` budget — Vistierie defaults
it to 1024 when the caller omits it, so thinking-heavy calls can truncate.
Set `effort: "off"` (or a generous `max_tokens`) on latency-sensitive
`claude-subscription` routes.

Both the `effort` field and `max_tokens` enforcement require a current
`claude-bridge` image — an older bridge silently ignores the unknown
`effort` field (no error, just no latency win), so redeploy the sidecar
when adopting this.

Off by default. Enable it only once the `claude-bridge` sidecar is deployed
and reachable at `base-url`.

**Error semantics:**

- Bridge returns HTTP `429` with `{"error":{"code":"subscription_exhausted",...}}`
  when the Max subscription's quota is exhausted for the window. Vistierie surfaces
  this as `ProviderException(429, "subscription_exhausted", ...)` — a routing rule's
  fallback provider can catch this and retry on `anthropic`.
- Any other bridge/SDK failure (e.g. `auth_expired`, transport errors, malformed
  response) is surfaced as `ProviderException(502, <code>, ...)` so it behaves like
  a normal upstream outage for routing/fallback purposes.
- A `subscription_exhausted` 429 also opens the global cooldown described in
  `cooldown-seconds` below: further calls whose primary provider is
  `claude-subscription` skip the bridge entirely and go straight to the
  fallback provider until the cooldown elapses.

**Timeout / cancellation:** The bridge bounds each SDK query at
`BRIDGE_QUERY_TIMEOUT_MS` (default `290000` ms — just under the Java 300s read
timeout so the bridge gives up first). On timeout, or when the incoming HTTP
request is aborted/closed by the caller, the bridge aborts the SDK query via an
`AbortController`, terminating the spawned CLI child process instead of leaking
it. A timeout is surfaced as HTTP `504` `{"error":{"code":"timeout",...}}`.

**Configuration:**

| Property | Env var | Default | Required |
|----------|---------|---------|----------|
| `vistierie.claude-subscription.enabled` | `CLAUDE_SUBSCRIPTION_ENABLED` | `false` | yes (to enable) |
| `vistierie.claude-subscription.base-url` | `CLAUDE_BRIDGE_URL` | `http://claude-bridge:8091` | no |
| `vistierie.claude-subscription.timeout-seconds` | — | `300` | no |
| `vistierie.claude-subscription.cooldown-seconds` | `CLAUDE_SUBSCRIPTION_COOLDOWN_SECONDS` | `3600` | no |
| _(bridge sidecar)_ query timeout | `BRIDGE_QUERY_TIMEOUT_MS` | `290000` | no |

**Typical pairing:** a routing rule targets `claude-subscription` as the primary
provider with `anthropic` configured as its fallback, so subscription-quota
exhaustion (`429`) or bridge/SDK failure (`502`) transparently falls back to the
metered API-key provider rather than failing the request.

**Supported model IDs:** Any Anthropic model string, e.g. `claude-opus-4-8`.

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
      timeout-seconds: 60   # HTTP read timeout, in seconds (defaults to 60; connect timeout is a fixed 5s)
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

**Tool choice:** when `ProviderRequest.toolChoice()` is set, it is mapped onto
Bedrock's `ToolConfiguration.toolChoice` so the model can be forced to use a tool
rather than merely offered one. Supported shapes: `{"type": "tool", "name": "..."}`
(forces that specific tool), `{"type": "any"}` (forces some tool), and
`{"type": "auto"}` (leaves it to the model, the Converse API default). Any other
or malformed shape leaves the choice unset — the tools are still offered via
`ToolConfiguration.tools`, just not forced — rather than failing the request.

> **Multi-image limit:** Bedrock's Converse API caps a single request at roughly 20 images.
> Vistierie does not enforce a hard cap — requests above the provider limit are rejected by
> Bedrock and surface as a `ProviderException` (same handling as `/llm/vision`). Callers that
> need more images batch them into multiple `/llm/vision-multi` requests.

**Authentication:** Standard AWS credential chain — environment variables
(`AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` / `AWS_SESSION_TOKEN`), shared
credentials file (`~/.aws/credentials`), EC2 instance profile, or ECS task role.
No `api-key` property is used. Bedrock API-key (ABSK) auth is also supported via the
`AWS_BEARER_TOKEN_BEDROCK` environment variable, read natively by the AWS SDK.

**Configuration:**

| Property | Env var | Default | Required |
|----------|---------|---------|----------|
| `vistierie.bedrock.enabled` | `BEDROCK_ENABLED` | `false` | yes (to enable) |
| `vistierie.bedrock.region` | `AWS_REGION` | SDK default | no |
| `vistierie.bedrock.read-timeout-seconds` | — | `180` | no |

```yaml
vistierie:
  bedrock:
    enabled: ${BEDROCK_ENABLED:false}
    region: ${AWS_REGION:}
    read-timeout-seconds: 180
```

**Supported model IDs:** Bedrock model ARNs/IDs, e.g.:
- `anthropic.claude-3-5-sonnet-20241022-v2:0`
- `amazon.nova-pro-v1:0`
- `amazon.titan-text-premier-v1:0`
- `mistral.mistral-large-2402-v1:0`

---

## Mock mode

Setting `vistierie.mock-llm=true` (env `VISTIERIE_MOCK_LLM`) disables the real
Anthropic provider and registers a stub **under the name `anthropic`**. The stub
returns canned `[mock] …` / `[mock vision] …` responses with fixed token usage
and never reaches a real API — used for integration testing without cost or
network. Routing rules that resolve to `anthropic` are served by the stub; the
`bedrock`, `openai`, and `xai` providers are unaffected (they remain real if
configured). See [configuration.md](configuration.md#feature-flags).

---

## Adding a provider

**OpenAI-compatible endpoint — no code.** Any API speaking the OpenAI
`/v1/chat/completions` wire format is added purely by config: declare a new block
under `vistierie.providers.<name>` (see above) and point a routing rule at
`<name>`. This covers most self-hosted and third-party gateways.

**A genuinely new provider type — implement the interface.** Add a Spring
`@Component` that implements `de.vesterion.vistierie.provider.LlmProvider`:

- `name()` — the routing string the provider is selected by.
- `complete(ProviderRequest)` and `vision(...)` — required.
- `visionMulti(...)`, `submitBatch(...)`, `getBatch(...)`, `streamResults(...)` —
  optional; the interface defaults throw `UnsupportedOperationException`, so a
  provider that doesn't support them still compiles.

`ProviderRegistry` auto-collects every `LlmProvider` bean by `name()` at startup,
so no manual registration is needed — just point a routing rule at the new name.
Throw `LlmProvider.ProviderException(statusCode, errorCode, msg)` for upstream
errors so they surface consistently in the audit trail.
