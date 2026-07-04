# Routing

Routing rules live in Postgres (`vistierie.routing_rules`) and are managed
exclusively by operators via the admin REST API. Tenants have read-only
visibility through their own audit trail (`llm_calls.provider`,
`llm_calls.model`).

## Resolution algorithm

For each `/llm/complete`, `/llm/vision`, and `/llm/vision-multi` call, the resolver:

1. Loads all rules for the call's tenant, sorted by `priority` ASC.
   (In-memory cache, invalidated on every admin write via a version counter.)
2. Filters to rules whose `realm` and `purpose` match the call. `NULL` in
   a rule field means "match any value".
3. Picks the lowest-priority rule. Ties are broken by specificity:
   `(realm + purpose)` > `(realm only)` > `(purpose only)` > `(both NULL)`.
4. If `requestedModel` is set AND the matched rule has `allow_override=true`
   AND `locked=false`, the request's `model` field replaces the rule's
   model. Otherwise the rule's `model` is used.
5. If no rule matches, returns 400 `no route`. This cannot happen under
   normal operation: tenant creation auto-seeds a wildcard default rule.

## Privacy lock pattern

A rule with `locked=true` ignores `allow_override` at resolution time,
the tenant cannot escape the rule by sending a `model` override. Use it
for realms whose data must not leave a specific provider.

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
     -H "Content-Type: application/json" \
     -X POST https://vistierie/admin/routing-rules \
     -d '{
       "tenant": "hivemem",
       "realm": "medical",
       "purpose": null,
       "provider": "ollama",
       "model": "llama-3.1-70b",
       "priority": 10,
       "allow_override": false,
       "locked": true
     }'
```

After this rule exists, every `/llm/complete` call from `hivemem` with
`realm: "medical"` resolves to `(provider=ollama, model=llama-3.1-70b)`,
regardless of any `model` override in the request body. The `llm_calls`
audit row records the actual provider+model used.

## Priority conventions

| Range | Use |
|---|---|
| 0–50 | Operator privacy locks |
| 50–200 | Realm-scoped rules |
| 200–800 | Purpose-scoped rules |
| 1000 | Tenant default (auto-seeded on tenant creation) |

`priority` is an integer in `[0, 10000]`. Lowest value wins.

## Fallback

A rule may optionally carry `fallback_provider` and `fallback_model`. These
are set (or cleared) through the same admin API used for the primary
`provider`/`model` fields.

- Both fields are optional, but they must be set **together**: setting only
  one returns 400 (`fallback_provider and fallback_model must be set
  together`).
- The fallback provider must be a known provider (validated against the same
  `ProviderRegistry` used for the primary provider) and must **differ** from
  the primary `provider` — a rule cannot fall back to itself.
- On `PATCH`, sending `"clear_fallback": true` removes an existing fallback
  (sets both fields back to `NULL`), regardless of what else is patched in
  the same request. Sending `fallback_provider`/`fallback_model` without
  `clear_fallback` replaces the fallback; omitting all three fallback fields
  leaves the existing fallback untouched.

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
     -H "Content-Type: application/json" \
     -X POST https://vistierie/admin/routing-rules \
     -d '{
       "tenant": "hivemem",
       "realm": null,
       "purpose": null,
       "provider": "claude-subscription",
       "model": "claude-opus-4-8",
       "fallback_provider": "anthropic",
       "fallback_model": "claude-opus-4-8",
       "priority": 500,
       "allow_override": false,
       "locked": false
     }'
```

```bash
# remove the fallback later:
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
     -H "Content-Type: application/json" \
     -X PATCH https://vistierie/admin/routing-rules/$RULE_ID \
     -d '{ "clear_fallback": true }'
```

### When fallback triggers

At call time, the resolved rule's primary `(provider, model)` is attempted
first. The fallback `(fallback_provider, fallback_model)` is attempted only
if the primary attempt fails with:

- HTTP 429 (rate limited), or
- HTTP ≥ 500 (upstream/server error), or
- an unsupported-operation error from the primary provider (e.g. the
  provider does not implement the requested capability, such as vision).

Any other 4xx (400, 401, 403, 404, ...) is treated as a genuine client/request
error and **never** triggers the fallback — it is returned to the caller
as-is.

Fallback is **one step only**: if the fallback attempt also fails, that
failure is returned to the caller. There is no chained fallback-of-a-fallback.

If the original request carried a `model` override (and the rule allows
override), that override is preserved on the fallback attempt too — the
fallback provider is called with the same overridden model, not with
`fallback_model`, unless `fallback_model` was itself the effective model.
See `RoutingDecision` for the exact precedence.

Both attempts are recorded as separate rows in `llm_calls`: one for the
primary provider/model and, if triggered, one for the fallback
provider/model. This keeps per-provider cost and latency accounting exact
even when a fallback occurred.

## Migration from the previous YAML config

The previous YAML-based routing has been removed. The single source of
truth is now the `routing_rules` table. New tenants auto-receive a
wildcard default rule pointing at `anthropic` / `claude-sonnet-4-6`;
operators add additional rules via `/admin/routing-rules`.

See `documentation/api.md` for endpoint details.
