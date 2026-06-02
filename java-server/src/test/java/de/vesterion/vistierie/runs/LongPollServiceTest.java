package de.vesterion.vistierie.runs;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.auth.AuthFilter;
import de.vesterion.vistierie.routing.RoutingRule;
import de.vesterion.vistierie.routing.RoutingRuleRepository;
import de.vesterion.vistierie.routing.RoutingResolver;
import de.vesterion.vistierie.tenants.TenantRepository;
import de.vesterion.vistierie.testsupport.OperationalBudgetFixtures;
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
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test-stub-llm")
class LongPollServiceTest extends PostgresTestBase {

    @Autowired WebApplicationContext wac;
    @Autowired AuthFilter authFilter;
    @Autowired TenantRepository tenants;
    @Autowired AgentRepository agents;
    @Autowired BCryptPasswordEncoder enc;
    @Autowired StubLlmProvider stub;
    @Autowired ObjectMapper mapper;
    @Autowired RoutingRuleRepository routingRules;
    @Autowired RoutingResolver routingResolver;
    @Autowired OperationalBudgetFixtures budgetFixtures;

    MockMvc mvc;
    String token;
    UUID tenantId;
    String tenantName;

    @BeforeEach void up() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).addFilter(authFilter).build();
        token = "tok-" + UUID.randomUUID();
        tenantId = UUID.randomUUID();
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

    @Test void longPollReturnsBeforeTimeoutWhenRunCompletes() throws Exception {
        var agentId = UUID.randomUUID();
        var schema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}},\"required\":[\"x\"]}");
        agents.insert(agentId, tenantId, "a", "p", "summarize_cell",
                mapper.createArrayNode(), schema, 3, 30, "wt", false, null, null, null, null, null, null);
        budgetFixtures.seed(tenantId, agentId);
        stub.script(StubLlmScripts.Turn.endTurn("{\"x\":\"v\"}"));
        var startResp = mvc.perform(post("/agents/a/run")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"payload\":{}}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        var runId = mapper.readTree(startResp).path("run_id").asText();

        long t0 = System.currentTimeMillis();
        var pollResp = mvc.perform(get("/runs/" + runId + "?wait_seconds=20")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON))
                .andReturn();
        mvc.perform(asyncDispatch(pollResp))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done"));
        long elapsed = System.currentTimeMillis() - t0;
        assertThat(elapsed).isLessThan(15_000);
    }
}
