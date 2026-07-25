package de.vesterion.vistierie.runs;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RunRepositoryTest extends PostgresTestBase {
    @Autowired RunRepository runs;
    @Autowired AgentRepository agents;
    @Autowired TenantRepository tenants;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;

    @Test void insertAndUpdateLifecycle() throws Exception {
        var tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tn-" + tenantId, "h");
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "a", "p", "purpose",
                JsonNodeFactory.instance.arrayNode(), null, 5, 60, "wt", false, null, null, null, null, null, null);

        var runId = "01J" + UUID.randomUUID().toString().replace("-", "").toUpperCase().substring(0, 23);
        var snapshot = mapper.readTree("{\"version\":1,\"name\":\"a\"}");
        runs.insert(runId, tenantId, agentId, snapshot, 1, null,
                "manual", "queued", null, null, null);

        var r = runs.findById(runId).orElseThrow();
        assertThat(r.status()).isEqualTo("queued");

        runs.markRunning(runId);
        runs.appendMessages(runId, mapper.readTree("[{\"role\":\"user\",\"content\":\"hi\"}]"));

        var r2 = runs.findById(runId).orElseThrow();
        assertThat(r2.status()).isEqualTo("running");
        assertThat(r2.messagesSnapshot().size()).isEqualTo(1);

        runs.markTerminal(runId, "done", mapper.readTree("{\"ok\":true}"), null, "done summary");
        var r3 = runs.findById(runId).orElseThrow();
        assertThat(r3.status()).isEqualTo("done");
        assertThat(r3.output().get("ok").asBoolean()).isTrue();
        assertThat(r3.finishedAt()).isNotNull();
    }

    @Test
    void hasOpenRunAndLatestOpenRunId() {
        var tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tn-" + tenantId, "h");
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "a", "p", "summarize_cell",
                mapper.createArrayNode(), null, 3, 30, "wt", false, null, null, null, null, null, null);

        assertThat(runs.hasOpenRun(agentId)).isFalse();
        assertThat(runs.latestOpenRunId(agentId)).isEmpty();

        var snap = mapper.createObjectNode();
        runs.insert("R1", tenantId, agentId, snap, 1, null, "manual", "queued",
                mapper.createObjectNode(), null, null);
        assertThat(runs.hasOpenRun(agentId)).isTrue();
        assertThat(runs.latestOpenRunId(agentId)).contains("R1");

        runs.markRunning("R1");
        runs.insert("R2", tenantId, agentId, snap, 1, null, "manual", "queued",
                mapper.createObjectNode(), null, null);
        // R2 is queued, R1 is running — both have started_at (V2 default now()); R2's is later.
        // CASE in latestOpenRunId prefers 'running', so R1 wins regardless of timestamp order.
        assertThat(runs.latestOpenRunId(agentId)).contains("R1");

        runs.markTerminal("R1", "done", null, null, null);
        runs.markTerminal("R2", "done", null, null, null);
        assertThat(runs.hasOpenRun(agentId)).isFalse();
    }

    /** Creates a tenant plus one agent and returns the agent id. */
    private UUID seedTenantWithAgent(UUID tenantId) {
        tenants.insert(tenantId, "tn-" + tenantId, "h");
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "a", "p", "purpose",
                JsonNodeFactory.instance.arrayNode(), null, 5, 60, "wt", false, null, null, null, null, null, null);
        return agentId;
    }

    /** Legt n Läufe für den Tenant an, started_at absteigend im Minutenabstand ab `base`. */
    private java.util.List<String> seedRuns(UUID tenantId, UUID agentId, int n, java.time.Instant base) {
        var ids = new java.util.ArrayList<String>();
        for (int i = 0; i < n; i++) {
            var runId = "01J" + UUID.randomUUID().toString().replace("-", "").toUpperCase().substring(0, 23);
            runs.insert(runId, tenantId, agentId, JsonNodeFactory.instance.objectNode(), 1, null,
                    "manual", "queued", null, null, null);
            jdbc.sql("UPDATE vistierie.runs SET started_at = ? WHERE id = ?")
                    .params(java.sql.Timestamp.from(base.minusSeconds(60L * i)), runId).update();
            ids.add(runId);
        }
        return ids; // ids.get(0) ist der NEUESTE
    }

    @Test void findByTenantPaginatesWithoutGapsOrDuplicates() {
        var tenantId = UUID.randomUUID();
        var agentId = seedTenantWithAgent(tenantId);
        var base = java.time.Instant.parse("2026-07-24T12:00:00Z");
        var expected = new java.util.HashSet<>(seedRuns(tenantId, agentId, 25, base));

        var seen = new java.util.ArrayList<String>();
        for (int offset = 0; offset < 30; offset += 10) {
            seen.addAll(runs.findByTenant(tenantId, 10, offset, null, null).stream().map(Run::id).toList());
        }

        // Menge statt Anzahl: deckt Lücken UND Dubletten auf.
        assertThat(seen).hasSize(25);
        assertThat(new java.util.HashSet<>(seen)).isEqualTo(expected);
    }

    @Test void findByTenantAppliesHalfOpenWindow() {
        var tenantId = UUID.randomUUID();
        var agentId = seedTenantWithAgent(tenantId);
        var base = java.time.Instant.parse("2026-07-24T12:00:00Z");
        seedRuns(tenantId, agentId, 5, base); // 12:00, 11:59, 11:58, 11:57, 11:56

        var got = runs.findByTenant(tenantId, 100, 0,
                java.time.Instant.parse("2026-07-24T11:57:00Z"),
                java.time.Instant.parse("2026-07-24T12:00:00Z"));

        // from inklusiv, to EXKLUSIV (wie /admin/runs): 11:57, 11:58, 11:59 — nicht 12:00, nicht 11:56.
        assertThat(got).hasSize(3);
        assertThat(got).extracting(Run::startedAt).containsExactly(
                java.time.Instant.parse("2026-07-24T11:59:00Z"),
                java.time.Instant.parse("2026-07-24T11:58:00Z"),
                java.time.Instant.parse("2026-07-24T11:57:00Z"));
    }

    @Test void findByTenantAppliesFromOnly() {
        var tenantId = UUID.randomUUID();
        var agentId = seedTenantWithAgent(tenantId);
        var base = java.time.Instant.parse("2026-07-24T12:00:00Z");
        var ids = seedRuns(tenantId, agentId, 5, base); // 12:00, 11:59, 11:58, 11:57, 11:56

        // from auf die Zeit des DRITTEN Laufs, to offen — der "alle Läufe seit X"-Aufruf.
        var got = runs.findByTenant(tenantId, 100, 0,
                java.time.Instant.parse("2026-07-24T11:58:00Z"), null);

        // from ist inklusiv: der dritte Lauf gehört dazu, die zwei älteren nicht.
        // IDs statt Anzahl — eine an die falsche Spaltenseite gebundene Grenze
        // (etwa <= statt >=) liefert hier ebenfalls Zeilen, aber die falschen.
        assertThat(got).extracting(Run::id).containsExactly(ids.get(0), ids.get(1), ids.get(2));
    }

    @Test void findByTenantAppliesToOnly() {
        var tenantId = UUID.randomUUID();
        var agentId = seedTenantWithAgent(tenantId);
        var base = java.time.Instant.parse("2026-07-24T12:00:00Z");
        var ids = seedRuns(tenantId, agentId, 5, base); // 12:00, 11:59, 11:58, 11:57, 11:56

        // to auf die Zeit des ZWEITEN Laufs, from offen.
        var got = runs.findByTenant(tenantId, 100, 0,
                null, java.time.Instant.parse("2026-07-24T11:59:00Z"));

        // to ist exklusiv: der zweite Lauf fehlt, die drei älteren sind da.
        assertThat(got).extracting(Run::id).containsExactly(ids.get(2), ids.get(3), ids.get(4));
    }

    @Test void findByTenantWithInvertedWindowIsEmpty() {
        var tenantId = UUID.randomUUID();
        var agentId = seedTenantWithAgent(tenantId);
        seedRuns(tenantId, agentId, 3, java.time.Instant.parse("2026-07-24T12:00:00Z"));

        var got = runs.findByTenant(tenantId, 100, 0,
                java.time.Instant.parse("2026-07-24T13:00:00Z"),
                java.time.Instant.parse("2026-07-24T11:00:00Z"));

        assertThat(got).isEmpty();
    }

    @Test void findByTenantNeverLeaksAnotherTenant() {
        var base = java.time.Instant.parse("2026-07-24T12:00:00Z");
        var mine = UUID.randomUUID();
        var myAgent = seedTenantWithAgent(mine);
        var myIds = new java.util.HashSet<>(seedRuns(mine, myAgent, 3, base));

        var theirs = UUID.randomUUID();
        var theirAgent = seedTenantWithAgent(theirs);
        seedRuns(theirs, theirAgent, 3, base);

        var got = runs.findByTenant(mine, 100, 0, null, null);

        assertThat(got).extracting(Run::id).containsExactlyInAnyOrderElementsOf(myIds);
    }

    @Test void findByTenantOldSignatureStillReturnsNewestFirst() {
        var tenantId = UUID.randomUUID();
        var agentId = seedTenantWithAgent(tenantId);
        var base = java.time.Instant.parse("2026-07-24T12:00:00Z");
        var ids = seedRuns(tenantId, agentId, 5, base); // ids.get(0) ist der neueste

        var got = runs.findByTenant(tenantId, 2);

        assertThat(got).extracting(Run::id).containsExactly(ids.get(0), ids.get(1));
    }
}
