# Operations

## Deployment with Docker Compose

The repository ships a `docker-compose.yml` that runs Postgres and the Vistierie
service on a private network. It joins the external `hivemem-net` so co-located
consumers can reach it without exposing a public port.

Required environment variables (compose fails fast if unset):

```bash
export VISTIERIE_DB_PASSWORD=...        # Postgres password
export VISTIERIE_ADMIN_TOKEN_HASH=...   # bcrypt hash of the admin bearer token
export ANTHROPIC_API_KEY=...            # at least one provider key
# optional: OPENAI_API_KEY, XAI_API_KEY, VISTIERIE_IMAGE, VISTIERIE_PORT

docker compose up -d
```

The image defaults to `ghcr.io/visterion/vistierie:main`; pin a release tag via
`VISTIERIE_IMAGE=ghcr.io/visterion/vistierie:v1.2.1` for reproducible deploys.

### LXC / Proxmox hosts

On unprivileged LXC containers (Proxmox/PVE) the kernel denies Unix-domain
socket creation, which breaks Postgres `pg_ctl` socket init and the JDK NIO
`UnixDispatcher`. Layer the `docker-compose.lxc.yml` override, which marks both
services `privileged: true`:

```bash
docker compose -f docker-compose.yml -f docker-compose.lxc.yml up -d
```

Do **not** use this override on standard VM or bare-metal Docker hosts — it
grants unnecessary privileges.

---

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
    "reason": "runaway cost spike, pausing for investigation",
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
4. Dracul service (and any other tenants)

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

## Metrics

Vistierie exposes Spring Boot Actuator with a Prometheus registry. The auth
filter bypasses `/actuator/**`, so the following endpoints are reachable
without a bearer token — keep the service behind a network ACL or restrict
exposure via `management.endpoints.web.exposure.include`.

| Endpoint | Purpose |
|---|---|
| `/actuator/health` | Liveness/readiness aggregate (terse, no details) |
| `/actuator/info` | Build/version info |
| `/actuator/prometheus` | OpenMetrics-format scrape target |

LLM-specific series (tagged by `provider`, `model`, `endpoint`, `status`):

| Metric | Type | Notes |
|---|---|---|
| `vistierie_llm_calls_total` | counter | One increment per `complete`/`vision` call |
| `vistierie_llm_latency_seconds` | timer/histogram | Wall-clock latency from request to provider response |
| `vistierie_llm_cost_micros_total` | counter | Cumulative cost in EUR-micros (skipped on error/killed) |

Standard JVM, HikariCP, Tomcat, and Flyway metrics ship out of the box.

Sample Prometheus scrape config:

```yaml
- job_name: vistierie
  metrics_path: /actuator/prometheus
  static_configs:
    - targets: [vistierie:8090]
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

### Batch vs on-demand spend

```sql
SELECT CASE WHEN batch_id IS NULL THEN 'on_demand' ELSE 'batched' END AS mode,
       SUM(cost_micros) / 1000000.0 AS eur,
       count(*)                       AS calls
FROM vistierie.llm_calls
WHERE tenant_id = ?
GROUP BY mode
ORDER BY eur DESC;
```

Batched calls are charged at 50 % of the on-demand rate (Anthropic Message
Batches API pricing). Use this query to monitor the cost mix and confirm
that batchable workloads are actually going through the batch endpoint.

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

### Cron caveats

- Vistierie uses an **in-process scheduler**. Running two Vistierie
  instances against the same database will cause double-firing, there's no
  leader election or DB lease in v1.
- A restart drops missed cron boundaries; `last_tick_at` is preserved but
  the scheduler computes the next fire from the current clock.
- The host clock matters. Deploy in **UTC** to keep cron expressions
  predictable across DST.

---

## Setting up a new tenant

1. Operator calls `POST /admin/tenants` with the tenant name. A default
   wildcard routing rule is auto-seeded
   (`provider=anthropic`, `model=claude-sonnet-4-6`, `priority=1000`).
2. Optionally add purpose-specific rules:
   ```bash
   curl -H "Authorization: Bearer $ADMIN_TOKEN" \
        -X POST https://vistierie/admin/routing-rules \
        -d '{"tenant":"hivemem","realm":null,"purpose":"summarize_cell",
             "provider":"anthropic","model":"claude-haiku-4-5",
             "priority":500,"allow_override":false,"locked":false}'
   ```
3. Optionally add realm-locks for sensitive realms (see below).

---

## Inspecting an LLM call

When you need to see the exact prompt and response for a specific call:

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
     https://vistierie/admin/llm-calls/<call-id> | jq .
```

The response includes `request_json` (full system + messages) and `response_text`. Vision images are stored as `image_redacted` placeholders with sha256 + byte count, actual base64 bytes are not persisted.

If `body_evicted: true`, the body has been deleted by retention. Default retention is 7 days. Increase via `vistierie.audit.body-retention-days` or set to `0` to keep forever.

## Reviewing cost over time

```bash
# Hourly rollup for the last day, grouped by model:
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
     "https://vistierie/admin/cost?granularity=hour&group_by=model&from=$(date -u -d '24 hours ago' +%FT%TZ)" | jq .

# Total spend per tenant over the last week:
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
     "https://vistierie/admin/cost?granularity=none&group_by=tenant" | jq .
```

---

## Privacy lock pattern

Use `locked=true` to prevent any tenant override of a routing rule. The
canonical example: route everything in `realm=medical` to a local Ollama
provider, regardless of the request's `model` field.

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
     -X POST https://vistierie/admin/routing-rules \
     -d '{"tenant":"hivemem","realm":"medical","purpose":null,
          "provider":"ollama","model":"llama-3.1-70b",
          "priority":10,"allow_override":false,"locked":true}'
```

After this rule is created, every `llm_calls` row for `realm=medical`
will show the locked provider+model, never the consumer's requested
model. Use `/admin/llm-calls?tenant=hivemem&realm=medical` to verify.
