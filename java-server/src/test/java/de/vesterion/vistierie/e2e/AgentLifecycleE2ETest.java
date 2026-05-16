package de.vesterion.vistierie.e2e;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test-stub-llm")
class AgentLifecycleE2ETest extends PostgresTestBase {

    @Autowired WebApplicationContext wac;
    @Autowired AuthFilter authFilter;
    @Autowired TenantRepository tenants;
    @Autowired AgentRepository agents;
    @Autowired TenantBudgetRepository tenantBudgets;
    @Autowired AgentBudgetRepository agentBudgets;
    @Autowired BCryptPasswordEncoder enc;
    @Autowired StubLlmProvider stub;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired RoutingRuleRepository routingRules;
    @Autowired RoutingResolver routingResolver;

    MockMvc mvc;
    String token;
    String tenantName;

    @BeforeEach void up() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).addFilter(authFilter).build();
        token = "tok-" + UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        tenantName = "tn-" + tenantId;
        tenants.insert(tenantId, tenantName, enc.encode(token));
        var now = Instant.now();
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, null,
                "anthropic", "claude-haiku-4-5", 1000, false, false, now, now));
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, "summarize_cell",
                "anthropic", "claude-haiku-4-5", 500, false, false, now, now));
        routingResolver.bumpVersion();
        stub.resetAll();
    }

    private UUID agentId(String name) {
        return agents.findByName(tenants.findByName(tenantName).orElseThrow().id(), name).orElseThrow().id();
    }

    private void seedOperationalBudget(UUID tenantId, UUID agentId, long tenantCapMicros, long agentCapMicros) {
        tenantBudgets.patch(tenantId, new BudgetPatchRequest(tenantCapMicros, tenantCapMicros * 10, 80, 90));
        agentBudgets.patch(agentId, new BudgetPatchRequest(agentCapMicros, agentCapMicros * 10, 80, 90));
    }

    @Test void createTriggerLongPollDone() throws Exception {
        var createBody = """
                { "name":"a", "system_prompt":"p", "model_purpose":"summarize_cell",
                  "tools":[],
                  "output_schema":{"type":"object","properties":{"x":{"type":"string"}},"required":["x"]},
                  "webhook_token":"wt" }
                """;
        mvc.perform(post("/agents")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated());
        var agentId = agentId("a");
        seedOperationalBudget(tenants.findByName(tenantName).orElseThrow().id(), agentId, 100_000L, 50_000L);

        stub.script(StubLlmScripts.Turn.endTurn("{\"x\":\"yes\"}"));
        var triggerResp = mvc.perform(post("/agents/a/run")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"payload\":{}}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        var runId = mapper.readTree(triggerResp).path("run_id").asText();

        MvcResult poll = mvc.perform(get("/runs/" + runId + "?wait_seconds=15")
                .header("Authorization", "Bearer " + token))
                .andReturn();
        mvc.perform(asyncDispatch(poll))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done"))
                .andExpect(jsonPath("$.output.x").value("yes"));
    }

    @Test void directCallConsumptionBlocksSubsequentRunOnSameAgentBudget() throws Exception {
        var createBody = """
                { "name":"a", "system_prompt":"p", "model_purpose":"summarize_cell",
                  "tools":[],
                  "output_schema":{"type":"object","properties":{"x":{"type":"string"}},"required":["x"]},
                  "webhook_token":"wt" }
                """;
        mvc.perform(post("/agents")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated());

        var tenantId = tenants.findByName(tenantName).orElseThrow().id();
        var agentId = agentId("a");
        seedOperationalBudget(tenantId, agentId, 100_000L, 50_000L);

        stub.script(StubLlmScripts.Turn.endTurn("direct"));
        mvc.perform(post("/llm/complete")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"agent_name":"a","purpose":"summarize_cell",
                         "messages":[{"role":"user","content":"hi"}],
                         "max_tokens":32}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("direct"));

        long consumed = jdbc.sql("""
                SELECT cost_micros
                FROM vistierie.llm_calls
                WHERE tenant_id = ? AND agent_id = ?
                ORDER BY created_at DESC
                LIMIT 1
                """).params(tenantId, agentId).query(Long.class).single();
        agentBudgets.patch(agentId, new BudgetPatchRequest(consumed, consumed * 10, 80, 90));

        mvc.perform(post("/agents/a/run")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"payload\":{}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("budget_exceeded_agent_daily"))
                .andExpect(jsonPath("$.agent_name").value("a"))
                .andExpect(jsonPath("$.tenant").value(tenantName));
    }
}
