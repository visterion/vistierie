package de.vesterion.vistierie.runs;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.budget.AgentBudgetRepository;
import de.vesterion.vistierie.budget.TenantBudgetRepository;
import de.vesterion.vistierie.budget.admin.dto.BudgetPatchRequest;
import de.vesterion.vistierie.auth.AuthFilter;
import de.vesterion.vistierie.routing.RoutingRule;
import de.vesterion.vistierie.routing.RoutingRuleRepository;
import de.vesterion.vistierie.routing.RoutingResolver;
import de.vesterion.vistierie.tenants.TenantRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test-stub-llm")
class RunTriggerControllerTest extends PostgresTestBase {

    @Autowired WebApplicationContext wac;
    @Autowired AuthFilter authFilter;
    @Autowired TenantRepository tenants;
    @Autowired AgentRepository agents;
    @Autowired TenantBudgetRepository tenantBudgets;
    @Autowired AgentBudgetRepository agentBudgets;
    @Autowired RunRepository runs;
    @Autowired BCryptPasswordEncoder enc;
    @Autowired StubLlmProvider stub;
    @Autowired ObjectMapper mapper;
    @Autowired RoutingRuleRepository routingRules;
    @Autowired RoutingResolver routingResolver;

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

    @Test void triggerReturns202AndAsyncRunReachesDone() throws Exception {
        var agentId = UUID.randomUUID();
        var schema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}},\"required\":[\"x\"]}");
        agents.insert(agentId, tenantId, "a", "you", "summarize_cell",
                mapper.createArrayNode(), schema, 3, 30, "wt", false, null);
        tenantBudgets.patch(tenantId, new BudgetPatchRequest(10_000L, 100_000L, 80, 90));
        agentBudgets.patch(agentId, new BudgetPatchRequest(5_000L, 50_000L, 80, 90));
        stub.script(StubLlmScripts.Turn.endTurn("{\"x\":\"yes\"}"));

        var resp = mvc.perform(post("/agents/a/run")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":{}}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("queued"))
                .andReturn().getResponse().getContentAsString();
        var runId = mapper.readTree(resp).path("run_id").asText();

        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() ->
                runs.findById(runId).map(r -> "done".equals(r.status())).orElse(false));
    }

    @Test void rejectsPausedAgent() throws Exception {
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "p", "p", "summarize_cell",
                mapper.createArrayNode(), null, 3, 30, "wt", true, null);
        mvc.perform(post("/agents/p/run")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":{}}"))
                .andExpect(status().isConflict());
    }

    @Test void manualRunReturnsForbiddenWhenAgentBudgetMissing() throws Exception {
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "writer", "you", "summarize_cell",
                mapper.createArrayNode(), null, 3, 30, "wt", false, null);
        tenantBudgets.patch(tenantId, new BudgetPatchRequest(10_000L, 100_000L, 80, 90));

        mvc.perform(post("/agents/writer/run")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":{}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("budget_missing_agent"));
    }
}
