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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Agent CRUD lifecycle and the paused gate. These tenant-facing endpoints (create /
 * get / list / replace / patch / delete and the paused flag that blocks runs) carry no
 * E2E coverage otherwise — a regression here breaks every consumer that manages agents.
 */
@ActiveProfiles("test-stub-llm")
class AgentCrudE2ETest extends PostgresTestBase {

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

    private void create(String name) throws Exception {
        var body = """
                { "name":"%s", "system_prompt":"p", "model_purpose":"summarize_cell",
                  "tools":[], "webhook_token":"wt" }
                """.formatted(name);
        mvc.perform(post("/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.paused").value(false))
                .andExpect(jsonPath("$.version").value(1));
    }

    private void seedBudget(String name) {
        var agentId = agents.findByName(tenantId, name).orElseThrow().id();
        tenantBudgets.patch(tenantId, new BudgetPatchRequest(100_000L, 1_000_000L, 80, 90));
        agentBudgets.patch(agentId, new BudgetPatchRequest(50_000L, 500_000L, 80, 90));
    }

    @Test
    void fullCrudLifecycle() throws Exception {
        create("a");

        // GET single
        mvc.perform(get("/agents/a").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("a"))
                .andExpect(jsonPath("$.system_prompt").value("p"));

        // LIST contains it
        mvc.perform(get("/agents").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='a')]").exists());

        // PUT replace — full definition, new system prompt, bumps version
        var put = """
                { "system_prompt":"replaced", "model_purpose":"summarize_cell",
                  "tools":[], "webhook_token":"wt2" }
                """;
        mvc.perform(put("/agents/a")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(put))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.system_prompt").value("replaced"))
                .andExpect(jsonPath("$.version").value(2));

        // PATCH partial — only system_prompt
        mvc.perform(patch("/agents/a")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"system_prompt\":\"patched\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.system_prompt").value("patched"))
                .andExpect(jsonPath("$.model_purpose").value("summarize_cell"));

        // DELETE
        mvc.perform(delete("/agents/a").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // GET after delete → 404
        mvc.perform(get("/agents/a").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void getUnknownAgentReturns404() throws Exception {
        mvc.perform(get("/agents/does-not-exist").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void pausingBlocksRunAndUnpausingRestoresIt() throws Exception {
        create("a");
        seedBudget("a");

        // Pause via PATCH.
        mvc.perform(patch("/agents/a")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paused\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(true));

        // A paused agent rejects runs with 409 Conflict.
        mvc.perform(post("/agents/a/run")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"payload\":{}}"))
                .andExpect(status().isConflict());

        // Unpause.
        mvc.perform(patch("/agents/a")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paused\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(false));

        // Runs are accepted again.
        stub.script(StubLlmScripts.Turn.endTurn("ok"));
        mvc.perform(post("/agents/a/run")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"payload\":{}}"))
                .andExpect(status().isAccepted());
    }
}
