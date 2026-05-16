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

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test-stub-llm")
class MultiTenantIsolationTest extends PostgresTestBase {

    @Autowired WebApplicationContext wac;
    @Autowired AuthFilter authFilter;
    @Autowired TenantRepository tenants;
    @Autowired AgentRepository agents;
    @Autowired TenantBudgetRepository tenantBudgets;
    @Autowired AgentBudgetRepository agentBudgets;
    @Autowired BCryptPasswordEncoder enc;
    @Autowired JdbcClient jdbc;
    @Autowired StubLlmProvider stub;
    @Autowired RoutingRuleRepository routingRules;
    @Autowired RoutingResolver routingResolver;

    MockMvc mvc;
    String tokenA, tokenB;
    UUID tenantIdA, tenantIdB;

    @BeforeEach void up() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).addFilter(authFilter).build();
        tokenA = "ta-" + UUID.randomUUID();
        tokenB = "tb-" + UUID.randomUUID();
        tenantIdA = UUID.randomUUID();
        tenantIdB = UUID.randomUUID();
        tenants.insert(tenantIdA, "tn-a-" + tenantIdA, enc.encode(tokenA));
        tenants.insert(tenantIdB, "tn-b-" + tenantIdB, enc.encode(tokenB));
        seedRouting(tenantIdA);
        seedRouting(tenantIdB);
        stub.resetAll();
    }

    private void seedRouting(UUID tenantId) {
        var now = Instant.now();
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, null,
                "anthropic", "claude-haiku-4-5", 1000, false, false, now, now));
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, "summarize_cell",
                "anthropic", "claude-haiku-4-5", 500, false, false, now, now));
        routingResolver.bumpVersion();
    }

    @Test void tenantBCannotSeeAagent() throws Exception {
        var body = """
                { "name":"a","system_prompt":"p","model_purpose":"summarize_cell",
                  "tools":[],"webhook_token":"wt"}
                """;
        mvc.perform(post("/agents").header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mvc.perform(get("/agents").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test void directBudgetsStayIsolatedAcrossTenantsWithSameAgentName() throws Exception {
        var body = """
                { "name":"shared","system_prompt":"p","model_purpose":"summarize_cell",
                  "tools":[],"webhook_token":"wt"}
                """;
        mvc.perform(post("/agents").header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/agents").header("Authorization", "Bearer " + tokenB)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        var agentA = agents.findByName(tenantIdA, "shared").orElseThrow();
        var agentB = agents.findByName(tenantIdB, "shared").orElseThrow();
        tenantBudgets.patch(tenantIdA, new BudgetPatchRequest(100_000L, 1_000_000L, 80, 90));
        tenantBudgets.patch(tenantIdB, new BudgetPatchRequest(100_000L, 1_000_000L, 80, 90));
        agentBudgets.patch(agentA.id(), new BudgetPatchRequest(50_000L, 500_000L, 80, 90));
        agentBudgets.patch(agentB.id(), new BudgetPatchRequest(50_000L, 500_000L, 80, 90));

        stub.script(
                StubLlmScripts.Turn.endTurn("a-1"),
                StubLlmScripts.Turn.endTurn("b-1")
        );

        mvc.perform(post("/llm/complete")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"agent_name":"shared","purpose":"summarize_cell",
                         "messages":[{"role":"user","content":"hi-a"}],
                         "max_tokens":16}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("a-1"));

        long consumedA = jdbc.sql("""
                SELECT cost_micros
                FROM vistierie.llm_calls
                WHERE tenant_id = ? AND agent_id = ?
                ORDER BY created_at DESC
                LIMIT 1
                """).params(tenantIdA, agentA.id()).query(Long.class).single();
        agentBudgets.patch(agentA.id(), new BudgetPatchRequest(consumedA, 500_000L, 80, 90));

        mvc.perform(post("/llm/complete")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"agent_name":"shared","purpose":"summarize_cell",
                         "messages":[{"role":"user","content":"hi-a2"}],
                         "max_tokens":16}
                        """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("budget_exceeded_agent_daily"))
                .andExpect(jsonPath("$.tenant").value("tn-a-" + tenantIdA));

        mvc.perform(post("/llm/complete")
                .header("Authorization", "Bearer " + tokenB)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"agent_name":"shared","purpose":"summarize_cell",
                         "messages":[{"role":"user","content":"hi-b"}],
                         "max_tokens":16}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("b-1"));

        long tenantBCalls = jdbc.sql("""
                SELECT count(*)
                FROM vistierie.llm_calls
                WHERE tenant_id = ? AND agent_id = ?
                """).params(tenantIdB, agentB.id()).query(Long.class).single();
        assertThat(tenantBCalls).isEqualTo(1L);
    }
}
