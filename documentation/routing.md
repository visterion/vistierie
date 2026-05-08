# Routing

## Config layout

Routing rules live in `routing.yaml` (or inline in `application.yaml`). The
top-level key is `routing`, with one entry per tenant under `tenants`:

```yaml
routing:
  tenants:
    <tenant-name>:
      default:
        provider: <provider-name>
        model:    <model-id>
        allow-override: true|false
      purposes:
        <purpose-string>:
          provider: <provider-name>
          model:    <model-id>
          allow-override: true|false
```

Each rule has three fields:

| Field | Description |
|---|---|
| `provider` | Which provider plugin handles the call (e.g. `anthropic`) |
| `model` | Model ID passed to the provider (e.g. `claude-haiku-4-5`) |
| `allow-override` | Whether the caller may override the model via the request's `model` field |

---

## Resolution order

1. Look up `routing.tenants.<tenant-name>.purposes.<purpose>`. If a match
   exists, use that rule.
2. Otherwise fall back to `routing.tenants.<tenant-name>.default`.
3. If neither exists the request fails with `400`.

**No realm matching in Slice 1.** The `realm` field is stored in the audit log
but does not affect routing. Per-realm overrides are a Slice 2 candidate.

---

## `allow-override` semantics

When the request body includes a `model` field:

- `allow-override: true` — the requested model replaces the resolved model.
  Use this for "free pick" purposes where the caller knows better.
- `allow-override: false` — the `model` field in the request is silently ignored.
  The configured model is always used, ensuring cost predictability.

---

## Current `hivemem` routing

```yaml
routing:
  tenants:
    hivemem:
      default:
        provider: anthropic
        model: claude-sonnet-4-6
        allow-override: false
      purposes:
        summarize_cell:
          provider: anthropic
          model: claude-haiku-4-5
          allow-override: false
        vision_attachment:
          provider: anthropic
          model: claude-haiku-4-5
          allow-override: false
        vision_diagram:
          provider: anthropic
          model: claude-sonnet-4-6
          allow-override: false
        free_pick:
          provider: anthropic
          model: claude-sonnet-4-6
          allow-override: true
```

Rationale:
- `summarize_cell` and `vision_attachment` use Haiku for cost efficiency —
  these are high-frequency, low-complexity calls.
- `vision_diagram` and the default use Sonnet — diagrams need stronger
  reasoning.
- `free_pick` allows the caller to override the model, e.g. to temporarily
  experiment with a different model without a config change.

---

## Adding purposes for new agents

Agent runs route through the same `routing.yaml`: the agent's
`model_purpose` is looked up under the tenant's `purposes` map. Before a
new agent can run, the operator must add a routing entry for its purpose.

Example for the HiveMem Bee/Queen agents:

```yaml
routing:
  tenants:
    hivemem:
      purposes:
        bee-isolation:
          provider: anthropic
          model: claude-haiku-4-5
          allow-override: false
        queen-curation:
          provider: anthropic
          model: claude-sonnet-4-6
          allow-override: false
```

Adding a routing entry is currently an operator-side change (file edit and
restart, or external file pickup). Tenant self-serve routing is out of
scope for Slice 2.
