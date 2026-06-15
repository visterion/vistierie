package de.vesterion.vistierie.transcript;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.agent.runner.AgentRunner;
import de.vesterion.vistierie.auth.AuthFilter;
import de.vesterion.vistierie.routing.RoutingResolver;
import de.vesterion.vistierie.routing.RoutingRule;
import de.vesterion.vistierie.routing.RoutingRuleRepository;
import de.vesterion.vistierie.tenants.TenantRepository;
import de.vesterion.vistierie.testsupport.OperationalBudgetFixtures;
import de.vesterion.vistierie.testsupport.StubLlmProvider;
import de.vesterion.vistierie.testsupport.StubLlmScripts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test-stub-llm")
class RunSearchControllerTest extends PostgresTestBase {

    static WireMockServer wm;

    @Autowired WebApplicationContext wac;
    @Autowired AuthFilter authFilter;
    @Autowired AgentRunner runner;
    @Autowired AgentRepository agents;
    @Autowired TenantRepository tenants;
    @Autowired BCryptPasswordEncoder enc;
    @Autowired StubLlmProvider stub;
    @Autowired ObjectMapper mapper;
    @Autowired RoutingRuleRepository routingRules;
    @Autowired RoutingResolver routingResolver;
    @Autowired OperationalBudgetFixtures budgetFixtures;

    MockMvc mvc;
    String token;
    UUID tenantId;

    @BeforeEach void up() throws Exception {
        if (wm == null) { wm = new WireMockServer(0); wm.start(); }
        configureFor("localhost", wm.port());
        wm.resetAll();
        stub.resetAll();
        mvc = MockMvcBuilders.webAppContextSetup(wac).addFilter(authFilter).build();
        token = "tok-" + UUID.randomUUID();
        tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tn-" + tenantId, enc.encode(token));
        var now = Instant.now();
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, null,
                "anthropic", "claude-haiku-4-5", 1000, false, false, now, now));
        routingResolver.bumpVersion();
    }

    @AfterEach void resetWm() { wm.resetAll(); }

    private void runWithFailingTool() throws Exception {
        stubFor(post(urlEqualTo("/tools/edgar")).willReturn(serverError()));
        var tools = mapper.createArrayNode();
        tools.add(mapper.valueToTree(Map.of(
                "name", "edgar", "description", "x", "input_schema", Map.of("type", "object"),
                "webhook_url", "http://localhost:" + wm.port() + "/tools/edgar")));
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "spin", "p", "routine",
                tools, null, 3, 60, "wt", false, null, null, null, null, null, null);
        budgetFixtures.seed(tenantId, agentId);
        stub.script(StubLlmScripts.Turn.toolUses(StubLlmScripts.Turn.toolUse("edgar", Map.of())));
        runner.startRunSync(tenantId, agentId, "manual", mapper.readTree("{}"), null, null, null);
    }

    @Test void searchFindsFailedRunByErrorAndFilter() throws Exception {
        runWithFailingTool();

        mvc.perform(get("/runs/search?q=tool_error").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].hasError").value(true))
                .andExpect(jsonPath("$.items[0].snippet").exists());

        mvc.perform(get("/runs/search?q=tool_error&has_error=true&agent=spin")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test void searchIsTenantScoped() throws Exception {
        runWithFailingTool();
        var other = "tok-" + UUID.randomUUID();
        tenants.insert(UUID.randomUUID(), "other-" + UUID.randomUUID(), enc.encode(other));
        mvc.perform(get("/runs/search?q=tool_error").header("Authorization", "Bearer " + other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }
}
