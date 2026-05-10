package de.vesterion.vistierie.audit.admin;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAuditRepositoryTest extends PostgresTestBase {

    @Autowired AdminAuditRepository repo;
    @Autowired TenantRepository tenants;
    @Autowired JdbcClient jdbc;

    UUID tenantId;
    String tenantName;
    UUID agentId;

    @BeforeEach void setUp() {
        tenantId = UUID.randomUUID();
        tenantName = "audit-repo-" + tenantId.toString().substring(0, 8);
        tenants.insert(tenantId, tenantName, "x");
        agentId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO vistierie.agents
                  (id, tenant_id, name, system_prompt, model_purpose, tools, output_schema,
                   max_turns, max_run_seconds, webhook_token, paused)
                VALUES (?, ?, ?, 'sys', 'p', '[]'::jsonb, NULL, 25, 1800, 'tok', false)
                """).params(agentId, tenantId, "agent-" + tenantId.toString().substring(0, 8))
                .update();
    }

    private String insertCall(String purpose, String realm, String provider, String model,
                              String endpoint, String status, long cost, String runId,
                              Instant createdAt) {
        var id = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO vistierie.llm_calls
                  (id, tenant_id, purpose, realm, provider, model, endpoint,
                   input_tokens, output_tokens, cost_micros, duration_ms, status, run_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, ?, 0, ?, ?, ?)
                """).params(id, tenantId, purpose, realm, provider, model, endpoint,
                        cost, status, runId, Timestamp.from(createdAt))
                .update();
        return id;
    }

    private String insertRun(String status, Instant startedAt, Instant finishedAt) {
        var runId = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        jdbc.sql("""
                INSERT INTO vistierie.runs
                  (id, tenant_id, agent_id, agent_snapshot, agent_version,
                   trigger, status, started_at, finished_at)
                VALUES (?, ?, ?, '{}'::jsonb, 1, 'manual', ?, ?, ?)
                """).params(runId, tenantId, agentId, status,
                        Timestamp.from(startedAt),
                        finishedAt == null ? null : Timestamp.from(finishedAt))
                .update();
        return runId;
    }

    @Test void findLlmCallsFiltersByCombinedCriteria() {
        var t = Instant.parse("2026-04-01T10:00:00Z");
        insertCall("summarize", "hivemem", "anthropic", "claude-haiku-4-5",
                "complete", "ok", 100, null, t);
        insertCall("summarize", "dracul",  "anthropic", "claude-haiku-4-5",
                "complete", "ok", 200, null, t);
        insertCall("classify",  "hivemem", "openai",    "gpt-5-mini",
                "complete", "error", 0, null, t);

        var hivemem = repo.findLlmCalls(tenantName, "hivemem", null, null, null, null,
                null, null, null, null, 50, 0);
        assertThat(hivemem).hasSize(2);

        var anthropic = repo.findLlmCalls(tenantName, null, null, "anthropic", null, null,
                null, null, null, null, 50, 0);
        assertThat(anthropic).hasSize(2);

        var errors = repo.findLlmCalls(tenantName, null, null, null, null, null,
                List.of("error"), null, null, null, 50, 0);
        assertThat(errors).hasSize(1).first()
                .satisfies(c -> assertThat(c.purpose()).isEqualTo("classify"));

        var combined = repo.findLlmCalls(tenantName, "hivemem", "summarize", "anthropic",
                "claude-haiku-4-5", "complete", List.of("ok"), null, null, null, 50, 0);
        assertThat(combined).hasSize(1);
    }

    @Test void findLlmCallsRespectsPagination() {
        var base = Instant.parse("2026-04-01T10:00:00Z");
        for (int i = 0; i < 5; i++) {
            insertCall("p", "r", "anthropic", "claude-haiku-4-5", "complete", "ok",
                    i, null, base.plusSeconds(i));
        }
        var page1 = repo.findLlmCalls(tenantName, null, null, null, null, null,
                null, null, null, null, 2, 0);
        var page2 = repo.findLlmCalls(tenantName, null, null, null, null, null,
                null, null, null, null, 2, 2);
        assertThat(page1).hasSize(2);
        assertThat(page2).hasSize(2);
        assertThat(page1.get(0).id()).isNotEqualTo(page2.get(0).id());
        // ORDER BY created_at DESC ⇒ page1 newer than page2
        assertThat(page1.get(0).created_at()).isAfter(page2.get(0).created_at());
    }

    @Test void findLlmCallsFiltersByDateWindow() {
        var t1 = Instant.parse("2026-04-01T10:00:00Z");
        var t2 = Instant.parse("2026-04-02T10:00:00Z");
        var t3 = Instant.parse("2026-04-03T10:00:00Z");
        insertCall("p", "r", "anthropic", "m", "complete", "ok", 0, null, t1);
        insertCall("p", "r", "anthropic", "m", "complete", "ok", 0, null, t2);
        insertCall("p", "r", "anthropic", "m", "complete", "ok", 0, null, t3);

        var window = repo.findLlmCalls(tenantName, null, null, null, null, null, null, null,
                t1.plusSeconds(1), t3, 50, 0);
        assertThat(window).hasSize(1); // only t2
    }

    @Test void countRunsRespectsFilters() {
        var base = Instant.parse("2026-04-01T10:00:00Z");
        insertRun("done", base, base.plusSeconds(1));
        insertRun("done", base.plusSeconds(10), base.plusSeconds(11));
        insertRun("failed", base.plusSeconds(20), base.plusSeconds(21));

        long all = repo.countRuns(tenantName, null, null, null, null);
        long done = repo.countRuns(tenantName, null, List.of("done"), null, null);
        assertThat(all).isEqualTo(3);
        assertThat(done).isEqualTo(2);
    }

    @Test void findRunsAggregatesLlmCallStats() {
        var startedAt = Instant.parse("2026-04-01T10:00:00Z");
        var finishedAt = startedAt.plusSeconds(2);
        var runId = insertRun("done", startedAt, finishedAt);
        insertCall("p", "r", "anthropic", "m", "complete", "ok", 1500, runId, startedAt);
        insertCall("p", "r", "anthropic", "m", "complete", "ok", 2500, runId, startedAt);

        var rows = repo.findRuns(tenantName, null, null, null, null, 50, 0);
        assertThat(rows).hasSize(1);
        var row = rows.get(0);
        assertThat(row.id()).isEqualTo(runId);
        assertThat(row.llm_calls_count()).isEqualTo(2);
        assertThat(row.total_cost_micros()).isEqualTo(4000);
        assertThat(row.duration_ms()).isEqualTo(2000L);
    }

    @Test void findCallDetailReturnsBodyMissingWhenNoBody() {
        var t = Instant.parse("2026-04-01T10:00:00Z");
        var id = insertCall("p", "r", "anthropic", "m", "complete", "ok", 0, null, t);

        var detail = repo.findCallDetail(id).orElseThrow();
        assertThat(detail.body_evicted()).isTrue();
        assertThat(detail.request_json()).isNull();
        assertThat(detail.response_text()).isNull();
    }

    @Test void findCallDetailReturnsBodyWhenPresent() {
        var t = Instant.parse("2026-04-01T10:00:00Z");
        var id = insertCall("p", "r", "anthropic", "m", "complete", "ok", 0, null, t);
        // Use a current created_at for the body so the row does not get swept by
        // LlmCallBodyRepositoryTest.deleteOlderThanRemovesOldOnly when test order
        // varies between filesystems.
        jdbc.sql("""
                INSERT INTO vistierie.llm_call_bodies (call_id, request_json, response_text, created_at)
                VALUES (?, ?::jsonb, ?, ?)
                """).params(id, "{\"hello\":\"world\"}", "response-text", Timestamp.from(Instant.now()))
                .update();

        var detail = repo.findCallDetail(id).orElseThrow();
        assertThat(detail.body_evicted()).isFalse();
        assertThat(detail.request_json()).isNotNull();
        assertThat(detail.request_json().get("hello").asString()).isEqualTo("world");
        assertThat(detail.response_text()).isEqualTo("response-text");
    }

    @Test void findCallDetailReturnsEmptyWhenMissing() {
        assertThat(repo.findCallDetail(UUID.randomUUID().toString())).isEmpty();
    }
}
