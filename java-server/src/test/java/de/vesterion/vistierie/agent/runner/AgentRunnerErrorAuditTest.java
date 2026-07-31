package de.vesterion.vistierie.agent.runner;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.audit.LlmCallRecorder;
import de.vesterion.vistierie.provider.LlmProvider;
import de.vesterion.vistierie.provider.ProviderRequest;
import de.vesterion.vistierie.provider.ProviderResponse;
import de.vesterion.vistierie.routing.RoutingRule;
import de.vesterion.vistierie.routing.RoutingRuleRepository;
import de.vesterion.vistierie.routing.RoutingResolver;
import de.vesterion.vistierie.runs.Run;
import de.vesterion.vistierie.runs.RunStore;
import de.vesterion.vistierie.tenants.TenantRepository;
import de.vesterion.vistierie.testsupport.OperationalBudgetFixtures;
import de.vesterion.vistierie.testsupport.StubLlmProvider;
import de.vesterion.vistierie.testsupport.StubLlmScripts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;

/**
 * A primary provider failure must leave a forensic trace in {@code llm_calls} even when a
 * fallback rescues the run — see {@code AgentRunner.recordTurnFailure}. Fixtures mirror
 * {@link AgentRunnerFallbackTest} (same {@code failing} primary + stub {@code anthropic}
 * fallback wiring); only the assertions differ.
 */
@ActiveProfiles("test-stub-llm")
class AgentRunnerErrorAuditTest extends PostgresTestBase {

    @TestConfiguration
    static class FailingPrimaryConfig {
        static final AtomicReference<RuntimeException> FAIL = new AtomicReference<>();

        @Bean
        LlmProvider failingPrimary() {
            return new LlmProvider() {
                @Override public String name() { return "failing"; }
                @Override public ProviderResponse complete(ProviderRequest req) {
                    var e = FAIL.get();
                    if (e != null) throw e;
                    throw new IllegalStateException("no failure scripted");
                }
                @Override public ProviderResponse vision(String m, int t, String mt, String b, String p) {
                    throw new UnsupportedOperationException("n/a");
                }
            };
        }
    }

    @Autowired AgentRunner runner;
    @Autowired AgentRepository agents;
    @Autowired TenantRepository tenants;
    @Autowired RunStore runStore;
    @Autowired StubLlmProvider stub;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired RoutingRuleRepository routingRules;
    @Autowired RoutingResolver routingResolver;
    @Autowired OperationalBudgetFixtures budgetFixtures;

    // Spy (not mock): delegates to the real recorder by default, so every test's normal audit
    // rows still land in the DB. Only anAuditWriteFailureDoesNotSwallowTheProviderException
    // stubs a throw on top of the real behaviour.
    @MockitoSpyBean LlmCallRecorder recorder;

    @BeforeEach void reset() {
        stub.resetAll();
        FailingPrimaryConfig.FAIL.set(null);
    }

    /** Routing rule: primary {@code failing} with a configured fallback to the stub {@code anthropic}. */
    private void registerRoutingWithFallback(UUID tenantId) {
        var now = Instant.now();
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, "summarize_cell",
                "failing", "x", "anthropic", "claude-haiku-4-5",
                500, false, false, now, now));
        routingResolver.bumpVersion();
    }

    /** Routing rule: primary {@code failing} with NO fallback. */
    private void registerRoutingNoFallback(UUID tenantId) {
        var now = Instant.now();
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, "summarize_cell",
                "failing", "x", 500, false, false, now, now));
        routingResolver.bumpVersion();
    }

    private UUID newAgent(UUID tenantId) {
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "a", "you are a", "summarize_cell",
                mapper.createArrayNode(), null, 5, 60, "wt", false, null, null, null, null, null, null);
        budgetFixtures.seed(tenantId, agentId);
        return agentId;
    }

    private String statusOf(String runId) {
        return runStore.get(runId).status();
    }

    @Test void primaryFailureWritesAnErrorRowEvenWhenTheFallbackSucceeds() throws Exception {
        var tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tn-" + tenantId, "h");
        registerRoutingWithFallback(tenantId);
        var agentId = newAgent(tenantId);
        // Ohne diese Zeile loescht der Fix die Forensik-Spur, mit der der Vorfall vom
        // 2026-07-29 ueberhaupt erst aufgeklaert wurde.
        FailingPrimaryConfig.FAIL.set(
                new LlmProvider.ProviderException(529, "upstream_api_error", "API Error: 529"));
        stub.script(StubLlmScripts.Turn.endTurn("done"));

        var runId = runner.startRunSync(tenantId, agentId, "manual",
                mapper.readTree("{}"), null, null, null);

        assertThat(statusOf(runId)).isEqualTo("done");
        var rows = jdbc.sql("SELECT id, status, error_code, provider FROM vistierie.llm_calls "
                          + "WHERE run_id = ? ORDER BY status").param(runId).query().listOfRows();
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(r -> r.get("status")).containsExactly("error", "ok");
        // Eigene ULID: mit wiederverwendeter callId haette die Erfolgszeile eine
        // DuplicateKeyException geworfen und einen geretteten Run in failed verwandelt.
        assertThat(rows.get(0).get("id")).isNotEqualTo(rows.get(1).get("id"));
        assertThat(rows.get(0).get("error_code")).isEqualTo("upstream_api_error");
    }

    @Test void a429IsLabelledRateLimitedAndA400IsNot() throws Exception {
        var tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tn-" + tenantId, "h");
        registerRoutingNoFallback(tenantId);
        var agentId = newAgent(tenantId);
        FailingPrimaryConfig.FAIL.set(
                new LlmProvider.ProviderException(400, "upstream_api_error", "API Error: 400"));

        // kein Fallback konfiguriert -> Run scheitert, aber die Zeile muss trotzdem da sein
        var runId = runner.startRunSync(tenantId, agentId, "manual",
                mapper.readTree("{}"), null, null, null);

        assertThat(statusOf(runId)).isEqualTo("failed");
        var row = jdbc.sql("SELECT status FROM vistierie.llm_calls WHERE run_id = ?")
                      .param(runId).query().singleRow();
        // NICHT "rate_limited": mit A1.2 ist erstmals ein 4xx != 429 erreichbar, und ein
        // invalid_request als rate_limited zu buchen vergiftet die Ratelimit-Dashboards.
        assertThat(row.get("status")).isEqualTo("error");
    }

    @Test void aFailingFallbackAlsoGetsItsOwnRow() throws Exception {
        var tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tn-" + tenantId, "h");
        registerRoutingWithFallback(tenantId);
        var agentId = newAgent(tenantId);
        FailingPrimaryConfig.FAIL.set(
                new LlmProvider.ProviderException(529, "upstream_api_error", "primary"));
        stub.failNextComplete(new LlmProvider.ProviderException(500, "bedrock_down", "fallback"));

        var runId = runner.startRunSync(tenantId, agentId, "manual",
                mapper.readTree("{}"), null, null, null);

        assertThat(statusOf(runId)).isEqualTo("failed");
        var rows = jdbc.sql("SELECT provider, error_code FROM vistierie.llm_calls WHERE run_id = ?")
                       .param(runId).query().listOfRows();
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(r -> r.get("error_code"))
                        .containsExactlyInAnyOrder("upstream_api_error", "bedrock_down");
    }

    @Test void anAuditWriteFailureDoesNotSwallowTheProviderException() throws Exception {
        var tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tn-" + tenantId, "h");
        registerRoutingWithFallback(tenantId);
        var agentId = newAgent(tenantId);
        FailingPrimaryConfig.FAIL.set(
                new LlmProvider.ProviderException(529, "upstream_api_error", "boom"));
        stub.script(StubLlmScripts.Turn.endTurn("done"));
        // LlmCallRecorder.insertWithBody ist @Transactional. Ohne den nicht-fatalen Wrapper
        // wuerde ein DB-Schluckauf die urspruengliche ProviderException ERSETZEN und den noch
        // moeglichen Fallback-Aufruf ueberspringen. isNull() matcht nur die Fehlerzeile (res ==
        // null); die Erfolgszeile des Fallbacks bleibt unangetastet.
        doThrow(new DataAccessResourceFailureException("db down"))
                .when(recorder).insertWithBody(any(), any(), isNull());

        var runId = runner.startRunSync(tenantId, agentId, "manual",
                mapper.readTree("{}"), null, null, null);   // muss durchlaufen, nicht scheitern

        assertThat(statusOf(runId)).isEqualTo("done");
    }

    @Test void unsupportedOperationIsLabelledCorrectly() throws Exception {
        var tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tn-" + tenantId, "h");
        registerRoutingWithFallback(tenantId);
        var agentId = newAgent(tenantId);
        // :228 faengt RuntimeException, nicht ProviderException — UnsupportedOperationException
        // erreicht denselben Block und darf nicht mit pe.errorCode() abstuerzen.
        FailingPrimaryConfig.FAIL.set(new UnsupportedOperationException("no vision"));
        stub.script(StubLlmScripts.Turn.endTurn("done"));

        var runId = runner.startRunSync(tenantId, agentId, "manual",
                mapper.readTree("{}"), null, null, null);

        assertThat(statusOf(runId)).isEqualTo("done");
        var row = jdbc.sql("SELECT error_code FROM vistierie.llm_calls "
                         + "WHERE run_id = ? AND status <> 'ok'").param(runId).query().singleRow();
        assertThat(row.get("error_code")).isEqualTo("unsupported_operation");
    }
}
