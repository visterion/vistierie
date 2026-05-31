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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test-stub-llm")
class MonthlyBudgetCapE2ETest extends PostgresTestBase {

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

    /**
     * Proves that the MONTHLY budget cap blocks calls while the daily cap stays open.
     *
     * BudgetEnforcer.checkOrThrow enforces caps in order:
     *   tenant daily → tenant monthly → agent daily → agent monthly
     * To trigger budget_exceeded_agent_monthly we keep tenant daily, tenant monthly,
     * and agent daily all HIGH, and set only agent monthly == consumed.
     */
    @Test void monthlyCapBlocksWhenDailyCapStaysOpen() throws Exception {
        // Step 1: Create agent with generous budgets and catch-all routing
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

        // Seed generous budgets: tenant and agent both get high daily and monthly caps
        long generousCap = 100_000_000L; // 100 units, far above any stub cost
        tenantBudgets.patch(tenantId, new BudgetPatchRequest(generousCap, generousCap, 80, 90));
        agentBudgets.patch(agentId, new BudgetPatchRequest(generousCap, generousCap, 80, 90));

        // Step 2: Make first /llm/complete call → 200
        stub.script(StubLlmScripts.Turn.endTurn("ok"));
        mvc.perform(post("/llm/complete")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"agent_name":"a","purpose":"summarize_cell",
                         "messages":[{"role":"user","content":"hi"}],
                         "max_tokens":32}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("ok"));

        // Step 3: Read the consumed cost_micros from the latest llm_calls row
        long consumed = jdbc.sql("""
                SELECT cost_micros FROM vistierie.llm_calls
                WHERE tenant_id = ? AND agent_id = ?
                ORDER BY created_at DESC LIMIT 1
                """).params(tenantId, agentId).query(Long.class).single();

        // Step 4: Patch the AGENT budget so that:
        //   - monthly cap == consumed  (remaining 0 → will block)
        //   - daily cap == consumed * 100  (stays HIGH → won't block)
        // Also keep TENANT budget very high so tenant caps don't interfere.
        long safeConsumed = Math.max(consumed, 1L);
        tenantBudgets.patch(tenantId, new BudgetPatchRequest(generousCap, generousCap, 80, 90));
        agentBudgets.patch(agentId, new BudgetPatchRequest(safeConsumed * 100, safeConsumed, 80, 90));

        // Step 5: Second /llm/complete call → expect 403 with budget_exceeded_agent_monthly
        // Note: this call is blocked BEFORE reaching the provider, so no stub script needed.
        mvc.perform(post("/llm/complete")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"agent_name":"a","purpose":"summarize_cell",
                         "messages":[{"role":"user","content":"hi"}],
                         "max_tokens":32}
                        """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("budget_exceeded_agent_monthly"))
                .andExpect(jsonPath("$.agent_name").value("a"))
                .andExpect(jsonPath("$.tenant").value(tenantName));
    }
}
