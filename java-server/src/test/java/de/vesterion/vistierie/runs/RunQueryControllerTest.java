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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

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
}
