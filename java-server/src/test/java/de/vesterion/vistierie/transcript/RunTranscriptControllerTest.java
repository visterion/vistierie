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
class RunTranscriptControllerTest extends PostgresTestBase {

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
    String runId;

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

        stubFor(post(urlEqualTo("/tools/finnhub")).willReturn(okJson("{\"output\":{\"count\":0}}")));
        var tools = mapper.createArrayNode();
        tools.add(mapper.valueToTree(Map.of(
                "name", "finnhub", "description", "n", "input_schema", Map.of("type", "object"),
                "webhook_url", "http://localhost:" + wm.port() + "/tools/finnhub")));
        var schema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}},\"required\":[\"x\"]}");
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "echo", "p", "routine",
                tools, schema, 5, 60, "wt", false, null, null, null, null, null, null);
        budgetFixtures.seed(tenantId, agentId);
        stub.script(
                StubLlmScripts.Turn.toolUses(StubLlmScripts.Turn.toolUse("finnhub", Map.of("q", "AAPL"))),
                StubLlmScripts.Turn.endTurn("{\"x\":\"done\"}"));
        runId = runner.startRunSync(tenantId, agentId, "manual", mapper.readTree("{}"), null, null, null);
    }

    @AfterEach void resetWm() { wm.resetAll(); }

    @Test void compactTranscriptHidesInputMessagesAndShowsToolIo() throws Exception {
        mvc.perform(get("/runs/" + runId + "/transcript").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turn_count").value(2))
                .andExpect(jsonPath("$.turns[0].tool_calls[0].name").value("finnhub"))
                .andExpect(jsonPath("$.turns[0].tool_calls[0].output.count").value(0))
                .andExpect(jsonPath("$.turns[0].llm_input_messages").doesNotExist())
                .andExpect(jsonPath("$.final_output.x").value("done"));
    }

    @Test void digestAggregates() throws Exception {
        mvc.perform(get("/runs/" + runId + "/transcript?view=digest").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tools[0].name").value("finnhub"))
                .andExpect(jsonPath("$.tools[0].count").value(1))
                .andExpect(jsonPath("$.token_total").isNumber());
    }

    @Test void fullIncludesInputMessages() throws Exception {
        mvc.perform(get("/runs/" + runId + "/transcript?view=full").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turns[0].llm_input_messages").isArray());
    }

    @Test void toolCallDrillDownReturnsUntruncated() throws Exception {
        var body = mvc.perform(get("/runs/" + runId + "/transcript?view=full")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        var toolUseId = mapper.readTree(body).path("turns").get(0)
                .path("tool_calls").get(0).path("tool_use_id").asText();
        mvc.perform(get("/runs/" + runId + "/tool-calls/" + toolUseId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.output.count").value(0));
    }

    @Test void otherTenantGets404() throws Exception {
        var otherToken = "tok-" + UUID.randomUUID();
        tenants.insert(UUID.randomUUID(), "other", enc.encode(otherToken));
        mvc.perform(get("/runs/" + runId + "/transcript").header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }
}
