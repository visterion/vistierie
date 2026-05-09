# Routing

Routing rules live in Postgres (`vistierie.routing_rules`) and are managed
exclusively by operators via the admin REST API. Tenants have read-only
visibility through their own audit trail (`llm_calls.provider`,
`llm_calls.model`).

## Resolution algorithm

For each `/llm/complete` and `/llm/vision` call, the resolver:

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

A rule with `locked=true` ignores `allow_override` at resolution time —
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

## Migration from the previous YAML config

The previous YAML-based routing has been removed. The single source of
truth is now the `routing_rules` table. New tenants auto-receive a
wildcard default rule pointing at `anthropic` / `claude-sonnet-4-6`;
operators add additional rules via `/admin/routing-rules`.

See `documentation/api.md` for endpoint details.
