package de.vesterion.vistierie.runs;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.auth.AuthFilter;
import de.vesterion.vistierie.routing.RoutingConfig;
import de.vesterion.vistierie.tenants.TenantRepository;
import de.vesterion.vistierie.testsupport.StubLlmProvider;
import de.vesterion.vistierie.testsupport.StubLlmScripts;
import org.awaitility.Awaitility;
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

import java.time.Duration;
import java.util.HashMap;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test-stub-llm")
class RunTriggerControllerTest extends PostgresTestBase {

    @Autowired WebApplicationContext wac;
    @Autowired AuthFilter authFilter;
    @Autowired TenantRepository tenants;
    @Autowired AgentRepository agents;
    @Autowired RunRepository runs;
    @Autowired BCryptPasswordEncoder enc;
    @Autowired StubLlmProvider stub;
    @Autowired ObjectMapper mapper;
    @Autowired RoutingConfig routingConfig;

    MockMvc mvc;
    String token;
    String tenantName;
    UUID tenantId;

    @BeforeEach void up() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).addFilter(authFilter).build();
        token = "tok-" + UUID.randomUUID();
        tenantId = UUID.randomUUID();
        tenantName = "tn-" + tenantId;
        tenants.insert(tenantId, tenantName, enc.encode(token));
        registerRouting(tenantName);
        stub.resetAll();
    }

    private void registerRouting(String tenantName) {
        var t = new RoutingConfig.TenantRouting();
        t.setPurposes(new HashMap<>());
        var rule = new RoutingConfig.Rule();
        rule.setProvider("anthropic");
        rule.setModel("claude-haiku-4-5");
        rule.setAllowOverride(false);
        t.getPurposes().put("summarize_cell", rule);
        t.setDefault(rule);
        routingConfig.getTenants().put(tenantName, t);
    }

    @Test void triggerReturns202AndAsyncRunReachesDone() throws Exception {
        var agentId = UUID.randomUUID();
        var schema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}},\"required\":[\"x\"]}");
        agents.insert(agentId, tenantId, "a", "you", "summarize_cell",
                mapper.createArrayNode(), schema, 3, 30, "wt", false);
        stub.script(StubLlmScripts.Turn.endTurn("{\"x\":\"yes\"}"));

        var resp = mvc.perform(post("/agents/a/run")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":{}}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("queued"))
                .andReturn().getResponse().getContentAsString();
        var runId = mapper.readTree(resp).path("run_id").asText();

        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() ->
                runs.findById(runId).map(r -> "done".equals(r.status())).orElse(false));
    }

    @Test void rejectsPausedAgent() throws Exception {
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "p", "p", "summarize_cell",
                mapper.createArrayNode(), null, 3, 30, "wt", true);
        mvc.perform(post("/agents/p/run")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":{}}"))
                .andExpect(status().isConflict());
    }
}
