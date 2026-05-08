# Operations

## Seeding a tenant

Create a tenant and capture its one-time token:

```bash
ADMIN_TOKEN="your-admin-token"
BASE_URL="http://localhost:8090"

curl -s -X POST "$BASE_URL/admin/tenants" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "hivemem"}' | tee /tmp/tenant.json | jq .
```

Example response:

```json
{
  "id":    "550e8400-e29b-41d4-a716-446655440000",
  "name":  "hivemem",
  "token": "a3f9c2d1e8b74c6f..."
}
```

> **The plaintext token is shown exactly once.** Store it in your secret manager
> (e.g. pass it directly into the consumer's environment via Docker secrets or
> a `.env` file on the host). If lost, you must delete and re-create the tenant.

Verify the token works:

```bash
TENANT_TOKEN=$(jq -r .token /tmp/tenant.json)
curl -s "$BASE_URL/llm/complete" \
  -H "Authorization: Bearer $TENANT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"purpose":"free_pick","messages":[{"role":"user","content":"ping"}]}'
```

---

## Kill-switch

### Activate (indefinite)

```bash
curl -s -X POST "$BASE_URL/admin/tenants/hivemem/kill" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "reason": "runaway cost spike — pausing for investigation",
    "setBy":  "ops@example.com"
  }'
# → 204 No Content
```

### Activate (with expiry)

```bash
curl -s -X POST "$BASE_URL/admin/tenants/hivemem/kill" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "reason": "scheduled maintenance window",
    "until":  "2026-05-06T02:00:00Z",
    "setBy":  "ops@example.com"
  }'
```

### Check status

```bash
curl -s "$BASE_URL/admin/tenants/hivemem/kill" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq .
```

### Clear

```bash
curl -s -X DELETE "$BASE_URL/admin/tenants/hivemem/kill" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
# → 204 No Content
```

---

## Monitoring

### Daily cost rollup

```sql
SELECT SUM(cost_micros) / 1000000.0 AS eur
FROM vistierie.llm_calls
WHERE tenant_id = ?
  AND created_at >= now() - interval '1 day';
```

Replace `?` with the tenant UUID from `vistierie.tenants`. For a named lookup:

```sql
SELECT SUM(lc.cost_micros) / 1000000.0 AS eur
FROM vistierie.llm_calls lc
JOIN vistierie.tenants t ON t.id = lc.tenant_id
WHERE t.name = 'hivemem'
  AND lc.created_at >= now() - interval '1 day';
```

### Recent failures

```sql
SELECT created_at, purpose, model, status, error_code
FROM vistierie.llm_calls
WHERE tenant_id = ?
  AND status <> 'ok'
ORDER BY created_at DESC
LIMIT 50;
```

### Top purposes by cost (last 7 days)

```sql
SELECT purpose,
       COUNT(*)                          AS calls,
       SUM(cost_micros) / 1000000.0     AS eur,
       AVG(duration_ms)                 AS avg_ms
FROM vistierie.llm_calls
WHERE tenant_id = ?
  AND created_at >= now() - interval '7 days'
GROUP BY purpose
ORDER BY eur DESC;
```

---

## Backup and restore ordering

**Always restore Vistierie before HiveMem (and before any other tenant).**

HiveMem's hot-path workers call Vistierie on startup and during normal operation.
If Vistierie is unavailable when HiveMem starts, its LLM-dependent components
will fail to initialise. Restore order:

1. Postgres (shared host DB, if applicable)
2. Vistierie service
3. HiveMem service
4. Draczl service (and any other tenants)

Verify Vistierie is ready before starting consumers:

```bash
curl -sf http://localhost:8090/readyz && echo "Vistierie ready"
```

---

## Upgrading

Flyway runs migrations automatically on startup. No manual schema changes are
needed for minor releases. Check the changelog for breaking migration notes
before upgrading across major versions.

After upgrade, verify liveness and readiness:

```bash
curl -sf http://localhost:8090/healthz
curl -sf http://localhost:8090/readyz
```

---

## Agent runs

### Cost rollup by run

```sql
SELECT run_id, SUM(cost_micros) / 1000000.0 AS eur
FROM vistierie.llm_calls
WHERE run_id IS NOT NULL
GROUP BY run_id
ORDER BY eur DESC
LIMIT 20;
```

### Failed runs for a tenant

```sql
SELECT id, agent_id, error, finished_at
FROM vistierie.runs
WHERE status = 'failed' AND tenant_id = ?
ORDER BY finished_at DESC
LIMIT 50;
```

### Walking a parent → child tree

```sql
SELECT id, agent_id, status, started_at, finished_at
FROM vistierie.runs
WHERE parent_run_id = ?
ORDER BY started_at;
```

Or via the API: `GET /runs/{id}` returns `children_summary` (status counts);
`GET /runs/{id}/events` lists `subagent_spawned` events with the
`child_run_id` to drill into.

### Long-poll caveat

`GET /runs/{id}?wait_seconds=N` is in-process. A Vistierie restart drops
all open polls. Clients MUST handle a `running` response (timeout reached
without terminal state) and re-poll instead of relying on the request to
block indefinitely.
