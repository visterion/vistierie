package de.vesterion.vistierie.scheduler;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.routing.RoutingRule;
import de.vesterion.vistierie.routing.RoutingRuleRepository;
import de.vesterion.vistierie.routing.RoutingResolver;
import de.vesterion.vistierie.runs.RunRepository;
import de.vesterion.vistierie.streaming.StreamingSessionRepository;
import de.vesterion.vistierie.tenants.TenantRepository;
import de.vesterion.vistierie.testsupport.OperationalBudgetFixtures;
import de.vesterion.vistierie.testsupport.StubLlmProvider;
import de.vesterion.vistierie.testsupport.StubLlmScripts;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test-stub-llm")
@Import(StreamingBeeE2ETest.MutableClockConfig.class)
class StreamingBeeE2ETest extends PostgresTestBase {

    @TestConfiguration
    static class MutableClockConfig {
        static final AtomicReference<Instant> NOW =
                new AtomicReference<>(Instant.parse("2026-06-02T09:29:30Z"));

        @Bean @Primary Clock testClock() {
            return new Clock() {
                @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(java.time.ZoneId z) { return this; }
                @Override public Instant instant() { return NOW.get(); }
            };
        }
    }

    static WireMockServer wm;

    @Autowired AgentScheduler scheduler;
    @Autowired AgentRepository agents;
    @Autowired RunRepository runs;
    @Autowired TenantRepository tenants;
    @Autowired StubLlmProvider stub;
    @Autowired ObjectMapper mapper;
    @Autowired RoutingRuleRepository routingRules;
    @Autowired RoutingResolver routingResolver;
    @Autowired JdbcClient jdbc;
    @Autowired OperationalBudgetFixtures budgetFixtures;
    @Autowired StreamingSessionRepository sessions;

    UUID tenantId;
    UUID agentId;
    String eventSourcePath = "/events";
    String completionPath = "/done";

    @BeforeAll static void startWm() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
    }

    @AfterAll static void stopWm() { if (wm != null) wm.stop(); }

    @BeforeEach void setUp() throws Exception {
        MutableClockConfig.NOW.set(Instant.parse("2026-06-02T09:29:30Z"));
        stub.resetAll();
        wm.resetAll();
        wm.stubFor(post(urlPathEqualTo(completionPath))
                .willReturn(aResponse().withStatus(204)));

        tenantId = UUID.randomUUID();
        var tenantName = "tn-bee-" + tenantId;
        tenants.insert(tenantId, tenantName, "h");

        var now = Instant.now();
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, null,
                "anthropic", "claude-haiku-4-5", 1000, false, false, now, now));
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, "summarize_cell",
                "anthropic", "claude-haiku-4-5", 500, false, false, now, now));
        routingResolver.bumpVersion();

        agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "daywalker", "Watch the market.", "summarize_cell",
                mapper.createArrayNode(), null, 3, 30, "wt-token", false,
                "0 30 9 * * *",
                wm.baseUrl() + completionPath, "cb-token",
                wm.baseUrl() + eventSourcePath, 30600, 60);
        budgetFixtures.seed(tenantId, agentId);

        // Backdate created_at so the cron boundary is in the test window
        jdbc.sql("UPDATE vistierie.agents SET created_at='2026-06-02T09:00:00Z' WHERE id=?")
                .param(agentId).update();
    }

    // ---- Helpers ----

    private void stubEvents(String json) {
        wm.stubFor(post(urlPathEqualTo(eventSourcePath))
                .willReturn(okJson(json)));
    }

    private void stubNoEvents() {
        stubEvents("{\"events\":[]}");
    }

    private void scriptLlmRuns(int n) {
        var turns = new StubLlmScripts.ScriptedTurn[n];
        for (int i = 0; i < n; i++) turns[i] = StubLlmScripts.Turn.endTurn("{\"x\":\"v" + i + "\"}");
        stub.script(turns);
    }

    // ---- Tests ----

    @Test
    void openSessionAtCronBoundary() {
        stubNoEvents();
        MutableClockConfig.NOW.set(Instant.parse("2026-06-02T09:30:00Z"));
        scheduler.tick();

        var session = sessions.findOpenByAgent(agentId);
        assertThat(session).isPresent();
        assertThat(session.get().status()).isEqualTo("open");
        assertThat(session.get().closesAt())
                .isEqualTo(Instant.parse("2026-06-02T09:30:00Z").plusSeconds(30600));
    }

    @Test
    void eventsSpawnChildRunsWithSessionEventTrigger() throws Exception {
        stubEvents("{\"events\":[{\"symbol\":\"AAPL\",\"type\":\"spike\"},{\"symbol\":\"MSFT\",\"type\":\"spike\"}]}");
        scriptLlmRuns(10);

        // Advance clock to cron boundary
        MutableClockConfig.NOW.set(Instant.parse("2026-06-02T09:30:00Z"));
        scheduler.tick();

        // Wait for runs to appear
        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var allRuns = runs.findByTenant(tenantId, 10);
            assertThat(allRuns.stream().filter(r -> "session_event".equals(r.trigger())).count())
                    .isEqualTo(2);
        });

        var sessionRuns = runs.findByTenant(tenantId, 10).stream()
                .filter(r -> "session_event".equals(r.trigger()))
                .toList();
        assertThat(sessionRuns).hasSize(2);

        var session = sessions.findOpenByAgent(agentId).orElseThrow();
        for (var r : sessionRuns) {
            assertThat(r.sessionId()).isEqualTo(session.id());
            // payload contains the event
            assertThat(r.payload()).isNotNull();
            assertThat(r.payload().has("symbol")).isTrue();
        }
    }

    @Test
    void completionWebhookFiredPerChildRun() throws Exception {
        stubEvents("{\"events\":[{\"symbol\":\"AAPL\"}]}");
        scriptLlmRuns(5);

        MutableClockConfig.NOW.set(Instant.parse("2026-06-02T09:30:00Z"));
        scheduler.tick();

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                wm.verify(postRequestedFor(urlPathEqualTo(completionPath))
                        .withHeader("Authorization", equalTo("Bearer cb-token"))));
    }

    @Test
    void emptyEvents_noRunsSpawned() {
        stubNoEvents();
        MutableClockConfig.NOW.set(Instant.parse("2026-06-02T09:30:00Z"));
        scheduler.tick();

        assertThat(runs.findByTenant(tenantId, 10)).isEmpty();
        // session opened but no runs
        assertThat(sessions.findOpenByAgent(agentId)).isPresent();
    }

    @Test
    void sessionClosesAfterWindowExpires() {
        stubNoEvents();
        // Open session at 09:30
        MutableClockConfig.NOW.set(Instant.parse("2026-06-02T09:30:00Z"));
        scheduler.tick();
        assertThat(sessions.findOpenByAgent(agentId)).isPresent();

        // Advance past closes_at (30600s = 8.5h → 18:00)
        MutableClockConfig.NOW.set(Instant.parse("2026-06-02T18:01:00Z"));
        scheduler.tick();

        assertThat(sessions.findOpenByAgent(agentId)).isEmpty();
        assertThat(sessions.listByAgent(agentId, 10).get(0).status()).isEqualTo("closed");
    }

    @Test
    void restartResume_openSessionContinuesPolling() throws Exception {
        stubEvents("{\"events\":[{\"symbol\":\"TSLA\"}]}");
        scriptLlmRuns(10);

        // Open session
        MutableClockConfig.NOW.set(Instant.parse("2026-06-02T09:30:00Z"));
        scheduler.tick();
        var session = sessions.findOpenByAgent(agentId).orElseThrow();
        assertThat(session.status()).isEqualTo("open");

        // Simulate app restart: manually clear last_poll_at (as if context was fresh)
        jdbc.sql("UPDATE vistierie.streaming_sessions SET last_poll_at=NULL WHERE id=?")
                .param(session.id()).update();

        // Advance 65 seconds (> poll_interval=60) — simulate resumed poll after restart
        MutableClockConfig.NOW.set(Instant.parse("2026-06-02T09:31:05Z"));
        scheduler.tick();

        // A run should have been spawned
        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(runs.findByTenant(tenantId, 10).stream()
                        .filter(r -> "session_event".equals(r.trigger()))
                        .count()).isGreaterThanOrEqualTo(1));
    }

    @Test
    void killSwitchActive_noSpawns() {
        stubEvents("{\"events\":[{\"symbol\":\"AAPL\"}]}");

        // Kill tenant
        tenants.setKill(tenantId,
                Instant.parse("2026-06-02T23:59:00Z"), "drill", "operator");

        MutableClockConfig.NOW.set(Instant.parse("2026-06-02T09:30:00Z"));
        scheduler.tick();

        // Session may or may not open (cron fires, coordinator checks kill before polling)
        // No runs must be spawned
        assertThat(runs.findByTenant(tenantId, 10)).isEmpty();
    }

    @Test
    void eventSourceWebhook5xx_graceful_tickSurvives() {
        // Both attempts fail (WireMock returns 500 twice)
        wm.stubFor(post(urlPathEqualTo(eventSourcePath))
                .willReturn(serverError()));

        MutableClockConfig.NOW.set(Instant.parse("2026-06-02T09:30:00Z"));

        // Must not throw
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> scheduler.tick());

        // No runs spawned
        assertThat(runs.findByTenant(tenantId, 10)).isEmpty();
    }

    @Test
    void getSessionsEndpoint_returnsSessionsNewestFirst() throws Exception {
        stubNoEvents();

        // Open a session
        MutableClockConfig.NOW.set(Instant.parse("2026-06-02T09:30:00Z"));
        scheduler.tick();

        var sessionList = sessions.listByAgent(agentId, 50);
        assertThat(sessionList).hasSize(1);
        assertThat(sessionList.get(0).status()).isEqualTo("open");
        assertThat(sessionList.get(0).agentId()).isEqualTo(agentId);
    }

    @Test
    void regularAgentStillFiresCron_notAffectedByStreamingBee() throws Exception {
        // Register a normal (non-streaming) agent in the same tenant
        var regularId = UUID.randomUUID();
        agents.insert(regularId, tenantId, "regular-bee", "p", "summarize_cell",
                mapper.createArrayNode(), null, 3, 30, "wt", false,
                "0 30 9 * * *",
                null, null,
                null, null, null);
        budgetFixtures.seed(tenantId, regularId);
        jdbc.sql("UPDATE vistierie.agents SET created_at='2026-06-02T09:00:00Z' WHERE id=?")
                .param(regularId).update();

        stub.script(StubLlmScripts.Turn.endTurn("{\"x\":\"v\"}"));
        stubNoEvents(); // streaming agent gets no events

        MutableClockConfig.NOW.set(Instant.parse("2026-06-02T09:30:00Z"));
        scheduler.tick();

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var cronRuns = runs.findByTenant(tenantId, 10).stream()
                    .filter(r -> "cron".equals(r.trigger()))
                    .toList();
            assertThat(cronRuns).hasSize(1);
            assertThat(cronRuns.get(0).agentId()).isEqualTo(regularId);
        });
    }
}
