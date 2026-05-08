package de.vesterion.vistierie.scheduler;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.routing.RoutingConfig;
import de.vesterion.vistierie.runs.RunRepository;
import de.vesterion.vistierie.tenants.TenantRepository;
import de.vesterion.vistierie.testsupport.StubLlmProvider;
import de.vesterion.vistierie.testsupport.StubLlmScripts;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test-stub-llm")
@Import(AgentSchedulerTest.MutableClockConfig.class)
class AgentSchedulerTest extends PostgresTestBase {

    @TestConfiguration
    static class MutableClockConfig {
        static final AtomicReference<Instant> NOW =
                new AtomicReference<>(Instant.parse("2026-05-08T00:00:30Z"));
        @Bean @Primary Clock testClock() {
            return new Clock() {
                @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(java.time.ZoneId zone) { return this; }
                @Override public Instant instant() { return NOW.get(); }
            };
        }
    }

    @Autowired AgentScheduler scheduler;
    @Autowired AgentRepository agents;
    @Autowired RunRepository runs;
    @Autowired TenantRepository tenants;
    @Autowired StubLlmProvider stub;
    @Autowired ObjectMapper mapper;
    @Autowired RoutingConfig routingConfig;
    @Autowired JdbcClient jdbc;

    UUID tenantId;
    UUID agentId;

    @BeforeEach void up() {
        stub.resetAll();
        MutableClockConfig.NOW.set(Instant.parse("2026-05-08T00:00:30Z"));
        tenantId = UUID.randomUUID();
        var tenantName = "tn-" + tenantId;
        tenants.insert(tenantId, tenantName, "h");
        var t = new RoutingConfig.TenantRouting();
        t.setPurposes(new HashMap<>());
        var rule = new RoutingConfig.Rule();
        rule.setProvider("anthropic");
        rule.setModel("claude-haiku-4-5");
        rule.setAllowOverride(false);
        t.getPurposes().put("summarize_cell", rule);
        t.setDefault(rule);
        routingConfig.getTenants().put(tenantName, t);

        agentId = UUID.randomUUID();
        var schema = readObjectSchema();
        agents.insert(agentId, tenantId, "a", "p", "summarize_cell",
                mapper.createArrayNode(), schema, 3, 30, "wt", false, null);
        // Set schedule and backdate created_at so the cron boundary falls within the test window
        jdbc.sql("UPDATE vistierie.agents SET schedule='0 * * * * *', created_at='2026-05-08T00:00:00Z' WHERE id=?")
                .param(agentId).update();
    }

    private tools.jackson.databind.JsonNode readObjectSchema() {
        try { return mapper.readTree("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}},\"required\":[\"x\"]}"); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void tickFiresAtCronBoundaryAndUpdatesLastTick() {
        stub.script(StubLlmScripts.Turn.endTurn("{\"x\":\"v\"}"));

        // 00:00:30 — before next boundary (00:01:00). No run.
        scheduler.tick();
        assertThat(runs.findByTenant(tenantId, 10)).isEmpty();

        // Advance to 00:01:00 — boundary reached.
        MutableClockConfig.NOW.set(Instant.parse("2026-05-08T00:01:00Z"));
        scheduler.tick();

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(runs.findByTenant(tenantId, 10))
                        .hasSize(1)
                        .allSatisfy(r -> assertThat(r.trigger()).isEqualTo("cron")));
        assertThat(agents.findById(agentId).orElseThrow().lastTickAt()).isNotNull();

        // Advance 30s within the same minute — no second run.
        MutableClockConfig.NOW.set(Instant.parse("2026-05-08T00:01:30Z"));
        scheduler.tick();
        assertThat(runs.findByTenant(tenantId, 10)).hasSize(1);
    }
}
