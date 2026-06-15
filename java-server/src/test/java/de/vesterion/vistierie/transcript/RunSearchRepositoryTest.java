package de.vesterion.vistierie.transcript;

import de.vesterion.vistierie.PostgresTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RunSearchRepositoryTest extends PostgresTestBase {

    @Autowired RunSearchRepository repo;
    @Autowired JdbcClient jdbc;

    UUID tenantId;
    UUID agentId;

    @BeforeEach void seed() {
        tenantId = UUID.randomUUID();
        agentId = UUID.randomUUID();
        jdbc.sql("INSERT INTO vistierie.tenants (id, name, token_hash) VALUES (?,?,?)")
                .params(tenantId, "t-" + tenantId.toString().substring(0, 8), "x").update();
        jdbc.sql("""
                INSERT INTO vistierie.agents (id, tenant_id, name, system_prompt, model_purpose,
                    tools, output_schema, max_turns, max_run_seconds, webhook_token, paused)
                VALUES (?,?,?,?,?,'[]'::jsonb, NULL, 5, 60, 'wt', false)
                """).params(agentId, tenantId, "echo", "p", "routine").update();
    }

    private String seedRun(String status) {
        var runId = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        jdbc.sql("""
                INSERT INTO vistierie.runs (id, tenant_id, agent_id, agent_snapshot, agent_version,
                    trigger, status) VALUES (?,?,?,'{}'::jsonb,1,'manual',?)
                """).params(runId, tenantId, agentId, status).update();
        return runId;
    }

    @Test void upsertThenSearchFindsByErrorString() {
        var runId = seedRun("failed");
        repo.upsert(runId, tenantId, agentId, "echo", "failed", true, Instant.now(),
                "finnhub returned tool_error 401 missing key for AAPL");

        var hits = repo.search(tenantId, "401", null, null, null, null, null, 10, 0);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).runId()).isEqualTo(runId);
        assertThat(hits.get(0).hasError()).isTrue();
        assertThat(hits.get(0).snippet()).contains("401");
    }

    @Test void filtersByHasErrorAndAgent() {
        var ok = seedRun("done");
        repo.upsert(ok, tenantId, agentId, "echo", "done", false, Instant.now(), "clean run for MSFT");
        var bad = seedRun("failed");
        repo.upsert(bad, tenantId, agentId, "echo", "failed", true, Instant.now(), "broken run for MSFT");

        var errsOnly = repo.search(tenantId, "MSFT", "echo", null, Boolean.TRUE, null, null, 10, 0);
        assertThat(errsOnly).extracting(RunSearchRepository.Hit::runId).containsExactly(bad);
    }

    @Test void tenantIsolation() {
        var runId = seedRun("done");
        repo.upsert(runId, tenantId, agentId, "echo", "done", false, Instant.now(), "secret token NVDA");
        var hits = repo.search(UUID.randomUUID(), "NVDA", null, null, null, null, null, 10, 0);
        assertThat(hits).isEmpty();
    }
}
