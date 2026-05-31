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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E2E coverage for POST /llm/vision.
 */
@ActiveProfiles("test-stub-llm")
class VisionE2ETest extends PostgresTestBase {

    @Autowired WebApplicationContext wac;
    @Autowired AuthFilter authFilter;
    @Autowired TenantRepository tenants;
    @Autowired AgentRepository agents;
    @Autowired TenantBudgetRepository tenantBudgets;
    @Autowired AgentBudgetRepository agentBudgets;
    @Autowired RoutingRuleRepository routingRules;
    @Autowired RoutingResolver routingResolver;
    @Autowired StubLlmProvider stub;

    MockMvc mvc;
    String token;
    UUID tenantId;

    @BeforeEach
    void up() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).addFilter(authFilter).build();
        token = "tok-" + UUID.randomUUID();
        tenantId = UUID.randomUUID();
        var tenantName = "tn-" + tenantId;
        tenants.insert(tenantId, tenantName, new BCryptPasswordEncoder().encode(token));
        var now = Instant.now();
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, null,
                "anthropic", "claude-haiku-4-5", 1000, false, false, now, now));
        routingResolver.bumpVersion();
        stub.resetAll();
    }

    @Test
    void visionHappyPath() throws Exception {
        // Create agent via API
        var createBody = """
                { "name":"writer", "system_prompt":"p", "model_purpose":"summarize_cell",
                  "tools":[], "webhook_token":"wt" }
                """;
        mvc.perform(post("/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated());

        // Seed budgets
        var agentId = agents.findByName(tenantId, "writer").orElseThrow().id();
        tenantBudgets.patch(tenantId, new BudgetPatchRequest(100_000L, 1_000_000L, 80, 90));
        agentBudgets.patch(agentId, new BudgetPatchRequest(50_000L, 500_000L, 80, 90));

        // Call /llm/vision
        var visionBody = """
                { "agent_name":"writer", "purpose":"summarize_cell",
                  "image":{"type":"base64","media_type":"image/png","data":"aGVsbG8="},
                  "prompt":"describe", "max_tokens":16 }
                """;
        mvc.perform(post("/llm/vision")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(visionBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("[stub vision]"));
    }

    @Test
    void visionWithoutAuthReturns401() throws Exception {
        mvc.perform(post("/llm/vision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "agent_name":"writer", "purpose":"summarize_cell",
                                  "image":{"type":"base64","media_type":"image/png","data":"aGVsbG8="},
                                  "prompt":"describe", "max_tokens":16 }
                                """))
                .andExpect(status().isUnauthorized());
    }
}
