package de.vesterion.vistierie.runs;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.auth.AuthFilter;
import de.vesterion.vistierie.routing.RoutingRule;
import de.vesterion.vistierie.routing.RoutingRuleRepository;
import de.vesterion.vistierie.routing.RoutingResolver;
import de.vesterion.vistierie.tenants.TenantRepository;
import de.vesterion.vistierie.testsupport.OperationalBudgetFixtures;
import de.vesterion.vistierie.testsupport.StubLlmProvider;
import de.vesterion.vistierie.testsupport.StubLlmScripts;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test-stub-llm")
class RunQueryControllerTest extends PostgresTestBase {

    @Autowired WebApplicationContext wac;
    @Autowired AuthFilter authFilter;
    @Autowired TenantRepository tenants;
    @Autowired AgentRepository agents;
    @Autowired RunRepository runs;
    @Autowired BCryptPasswordEncoder enc;
    @Autowired StubLlmProvider stub;
    @Autowired ObjectMapper mapper;
    @Autowired RoutingRuleRepository routingRules;
    @Autowired RoutingResolver routingResolver;
    @Autowired OperationalBudgetFixtures budgetFixtures;
    @Autowired JdbcClient jdbc;

    MockMvc mvc;
    String token;
    String tenantName;
    UUID tenantId;

    @BeforeEach void up() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).addFilter(authFilter).build();
        token = "tok-" + UUID.randomUUID();
        tenantId = UUID.randomUUID();
        tenantName = "tn-" + tenantId;
        tenants.insert(tenantId, tenantName, enc.encode(token));
        registerRouting(tenantId);
        stub.resetAll();
    }

    private void registerRouting(UUID tId) {
        var now = Instant.now();
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tId, null, null,
                "anthropic", "claude-haiku-4-5", 1000, false, false, now, now));
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tId, null, "summarize_cell",
                "anthropic", "claude-haiku-4-5", 500, false, false, now, now));
        routingResolver.bumpVersion();
    }

    @Test void getRunReturnsTerminalState() throws Exception {
        var agentId = UUID.randomUUID();
        var schema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}},\"required\":[\"x\"]}");
        agents.insert(agentId, tenantId, "a", "p", "summarize_cell",
                mapper.createArrayNode(), schema, 3, 30, "wt", false, null, null, null, null, null, null);
        budgetFixtures.seed(tenantId, agentId);
        stub.script(StubLlmScripts.Turn.endTurn("{\"x\":\"yes\"}"));

        var startResp = mvc.perform(post("/agents/a/run")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"payload\":{}}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        var runId = mapper.readTree(startResp).path("run_id").asText();

        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() ->
                runs.findById(runId).map(r -> "done".equals(r.status())).orElse(false));

        var detailRaw = mvc.perform(get("/runs/" + runId).header("Authorization", "Bearer " + token))
                .andReturn();
        mvc.perform(asyncDispatch(detailRaw))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run_id").value(runId))
                .andExpect(jsonPath("$.status").value("done"))
                .andExpect(jsonPath("$.output.x").value("yes"));

        mvc.perform(get("/runs/" + runId + "/events").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mvc.perform(get("/runs").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].run_id").value(runId));
    }

    @Test void otherTenantCannotSeeRun() throws Exception {
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "secret", "p", "summarize_cell",
                mapper.createArrayNode(),
                mapper.readTree("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}},\"required\":[\"x\"]}"),
                3, 30, "wt", false, null, null, null, null, null, null);
        budgetFixtures.seed(tenantId, agentId);
        stub.script(StubLlmScripts.Turn.endTurn("{\"x\":\"v\"}"));
        var startResp = mvc.perform(post("/agents/secret/run")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"payload\":{}}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        var runId = mapper.readTree(startResp).path("run_id").asText();
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() ->
                runs.findById(runId).map(r -> "done".equals(r.status())).orElse(false));

        var otherToken = "tok-" + UUID.randomUUID();
        var otherId = UUID.randomUUID();
        tenants.insert(otherId, "tn-" + otherId, enc.encode(otherToken));
        var resp = mvc.perform(get("/runs/" + runId).header("Authorization", "Bearer " + otherToken))
                .andReturn();
        mvc.perform(asyncDispatch(resp))
                .andExpect(status().isNotFound());
    }

    // --- /runs pagination -------------------------------------------------
    // Das @BeforeEach-Fixture legt für jeden Test einen FRISCHEN Tenant an und
    // erzeugt selbst keine Läufe. /runs ist tenant-scoped, die Erwartungswerte
    // unten zählen also ausschließlich die hier geseedeten Läufe.

    /** Ein Agent für den aktuellen Tenant; Name eindeutig, kein Schedule (kein Scheduler-Anlauf). */
    private UUID seedAgent() {
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "seed-" + agentId, "p", "purpose",
                mapper.createArrayNode(), null, 5, 60, "wt", false, null, null, null, null, null, null);
        return agentId;
    }

    /**
     * Legt n Läufe für den aktuellen Tenant an, started_at absteigend im Minutenabstand ab `base`.
     * runs.insert setzt started_at auf now(); der Wert wird danach per SQL nachgezogen
     * (gleiche Technik wie RunRepositoryTest.seedRuns).
     */
    private void seedRunsAtMinutes(String baseIso, int n) {
        var base = Instant.parse(baseIso);
        var agentId = seedAgent();
        for (int i = 0; i < n; i++) {
            var runId = "01J" + UUID.randomUUID().toString().replace("-", "").toUpperCase().substring(0, 23);
            runs.insert(runId, tenantId, agentId, mapper.createObjectNode(), 1, null,
                    "manual", "queued", null, null, null);
            jdbc.sql("UPDATE vistierie.runs SET started_at = ? WHERE id = ?")
                    .params(java.sql.Timestamp.from(base.minusSeconds(60L * i)), runId).update();
        }
    }

    private void seedRunsForTenant(int n) {
        seedRunsAtMinutes("2026-07-24T12:00:00Z", n);
    }

    @Test void runsDefaultsAreUnchangedWithoutParams() throws Exception {
        seedRunsForTenant(120);
        mvc.perform(get("/runs").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(100));
    }

    @Test void runsHonorsLimitAndOffsetWithoutGapsOrDuplicates() throws Exception {
        seedRunsForTenant(25);
        var ids = new java.util.HashSet<String>();
        for (int offset = 0; offset < 30; offset += 10) {
            var body = mvc.perform(get("/runs")
                            .param("limit", "10").param("offset", String.valueOf(offset))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            for (var n : mapper.readTree(body)) ids.add(n.get("run_id").asText());
        }
        assertThat(ids).hasSize(25);   // Menge statt Anzahl: deckt Lücken UND Dubletten auf
    }

    @Test void runsClampsLimitAndOffset() throws Exception {
        seedRunsForTenant(250);
        mvc.perform(get("/runs").param("limit", "500")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(200));   // > 200 -> 200
        mvc.perform(get("/runs").param("limit", "0")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(100));   // < 1 -> Default 100
        mvc.perform(get("/runs").param("offset", "-5").param("limit", "3")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(3));     // < 0 -> 0, kein Fehler
    }

    @Test void runsAppliesTimeWindow() throws Exception {
        seedRunsAtMinutes("2026-07-24T12:00:00Z", 5); // 12:00, 11:59, 11:58, 11:57, 11:56
        mvc.perform(get("/runs")
                        .param("from", "2026-07-24T11:57:00Z")
                        .param("to", "2026-07-24T12:00:00Z")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));     // from inklusiv, to exklusiv
    }

    @Test void runsRejectsUnparsableTimestamp() throws Exception {
        mvc.perform(get("/runs").param("from", "gestern")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test void runsOffsetBeyondEndReturnsEmptyArray() throws Exception {
        seedRunsForTenant(5);
        mvc.perform(get("/runs").param("offset", "100")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
