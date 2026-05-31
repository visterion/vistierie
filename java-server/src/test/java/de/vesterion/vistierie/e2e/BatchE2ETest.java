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

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * E2E coverage for POST /agents/{name}/batch.
 * Uses the test-stub-llm profile so StubLlmProvider.submitBatch is wired in.
 */
@ActiveProfiles("test-stub-llm")
class BatchE2ETest extends PostgresTestBase {

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
    String tenantName;

    @BeforeEach
    void up() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).addFilter(authFilter).build();
        token = "tok-" + UUID.randomUUID();
        tenantId = UUID.randomUUID();
        tenantName = "tn-" + tenantId;
        tenants.insert(tenantId, tenantName, new BCryptPasswordEncoder().encode(token));
        var now = Instant.now();
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, null,
                "anthropic", "claude-haiku-4-5", 1000, false, false, now, now));
        routingResolver.bumpVersion();
        stub.resetAll();
    }

    /** Creates an agent via the API; returns its name. */
    private void createAgent(String name, boolean withOutputSchema) throws Exception {
        String schemaFragment = withOutputSchema
                ? """
                  "output_schema":{"type":"object","properties":{"x":{"type":"string"}},"required":["x"]},
                  """
                : "";
        var body = """
                { "name":"%s", "system_prompt":"p", "model_purpose":"summarize_cell",
                  "tools":[], %s"webhook_token":"wt" }
                """.formatted(name, schemaFragment);
        mvc.perform(post("/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void seedBudget(String agentName) {
        var agentId = agents.findByName(tenantId, agentName).orElseThrow().id();
        tenantBudgets.patch(tenantId, new BudgetPatchRequest(100_000L, 1_000_000L, 80, 90));
        agentBudgets.patch(agentId, new BudgetPatchRequest(50_000L, 500_000L, 80, 90));
    }

    // -----------------------------------------------------------------------
    // Test cases
    // -----------------------------------------------------------------------

    @Test
    void happyPath_returns202WithExpectedBody() throws Exception {
        createAgent("batch-agent", true);
        seedBudget("batch-agent");

        var body = """
                {"items":[{"payload":{"x":1}},{"payload":{"x":2}}]}
                """;

        mvc.perform(post("/agents/batch-agent/batch")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("queued"))
                .andExpect(jsonPath("$.items_total").value(2))
                .andExpect(jsonPath("$.anthropic_batch_id", startsWith("stubbatch_")));
    }

    @Test
    void unknownAgent_returns404() throws Exception {
        var body = """
                {"items":[{"payload":{"x":1}}]}
                """;

        mvc.perform(post("/agents/does-not-exist/batch")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void emptyItems_returns400() throws Exception {
        createAgent("batch-agent-empty", true);
        seedBudget("batch-agent-empty");

        mvc.perform(post("/agents/batch-agent-empty/batch")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void agentMissingOutputSchema_returns400() throws Exception {
        createAgent("batch-no-schema", false);
        seedBudget("batch-no-schema");

        var body = """
                {"items":[{"payload":{"x":1}}]}
                """;

        mvc.perform(post("/agents/batch-no-schema/batch")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }
}
