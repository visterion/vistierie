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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full kill-switch round-trip: a tenant call works, the admin kills the tenant, the
 * call is rejected with {@code killed}, the admin clears the kill, and the call works
 * again. This is the most important emergency control — if it silently breaks, an
 * operator believes a runaway consumer is stopped when it is not.
 */
class KillSwitchE2ETest extends PostgresTestBase {

    static final String ADMIN = "admin-e2e";

    @DynamicPropertySource
    static void p(DynamicPropertyRegistry r) {
        r.add("vistierie.admin.token-hash",
                () -> new BCryptPasswordEncoder().encode(ADMIN));
        // Force the MockProvider so we don't hit real Anthropic.
        r.add("vistierie.mock-llm", () -> "true");
    }

    @Autowired WebApplicationContext wac;
    @Autowired AuthFilter authFilter;
    @Autowired TenantRepository tenants;
    @Autowired AgentRepository agents;
    @Autowired TenantBudgetRepository tenantBudgets;
    @Autowired AgentBudgetRepository agentBudgets;
    @Autowired RoutingRuleRepository routingRules;
    @Autowired RoutingResolver routingResolver;

    MockMvc mvc;
    UUID tenantId;
    String tenantName;
    String tenantToken;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).addFilter(authFilter).build();
        tenantId = UUID.randomUUID();
        tenantName = "hivemem-e2e-" + tenantId.toString().substring(0, 8);
        tenantToken = "tok-" + tenantId;
        tenants.insert(tenantId, tenantName, new BCryptPasswordEncoder().encode(tenantToken));
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "writer", "sys", "summarize_cell",
                new tools.jackson.databind.ObjectMapper().createArrayNode(), null, 5, 60, "wt", false, null, null, null);
        // Operational budgets so the call passes the budget gate and reaches the kill check.
        tenantBudgets.patch(tenantId, new BudgetPatchRequest(100_000L, 1_000_000L, 80, 90));
        agentBudgets.patch(agentId, new BudgetPatchRequest(50_000L, 500_000L, 80, 90));
        var now = Instant.now();
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, null,
                "anthropic", "claude-haiku-4-5", 1000, false, false, now, now));
        routingResolver.bumpVersion();
    }

    @Test
    void killBlocksTenantCallsAndClearRestoresThem() throws Exception {
        var callBody = """
                { "agent_name":"writer", "purpose":"summarize_cell",
                  "messages":[{"role":"user","content":"x"}], "max_tokens":16 }
                """;

        // 1. Baseline — the tenant can call /llm/complete.
        completeCall(callBody).andExpect(status().isOk());

        // 2. Admin kills the tenant.
        var killBody = """
                { "reason":"runaway consumer", "set_by":"oncall" }
                """;
        mvc.perform(post("/admin/tenants/" + tenantName + "/kill")
                        .header("Authorization", "Bearer " + ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(killBody))
                .andExpect(status().isNoContent());

        // 3. The kill status reflects the active kill.
        mvc.perform(get("/admin/tenants/" + tenantName + "/kill")
                        .header("Authorization", "Bearer " + ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("runaway consumer"));

        // 4. The tenant call is now rejected with the killed error.
        completeCall(callBody)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("killed"))
                .andExpect(jsonPath("$.reason").value("runaway consumer"));

        // 5. Admin clears the kill.
        mvc.perform(delete("/admin/tenants/" + tenantName + "/kill")
                        .header("Authorization", "Bearer " + ADMIN))
                .andExpect(status().isNoContent());

        // 6. The tenant can call again.
        completeCall(callBody).andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions completeCall(String body) throws Exception {
        return mvc.perform(post("/llm/complete")
                .header("Authorization", "Bearer " + tenantToken)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
