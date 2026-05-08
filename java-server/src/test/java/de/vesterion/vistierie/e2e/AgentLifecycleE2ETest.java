package de.vesterion.vistierie.e2e;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.auth.AuthFilter;
import de.vesterion.vistierie.routing.RoutingConfig;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test-stub-llm")
class AgentLifecycleE2ETest extends PostgresTestBase {

    @Autowired WebApplicationContext wac;
    @Autowired AuthFilter authFilter;
    @Autowired TenantRepository tenants;
    @Autowired BCryptPasswordEncoder enc;
    @Autowired StubLlmProvider stub;
    @Autowired ObjectMapper mapper;
    @Autowired RoutingConfig routingConfig;

    MockMvc mvc;
    String token;
    String tenantName;

    @BeforeEach void up() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).addFilter(authFilter).build();
        token = "tok-" + UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        tenantName = "tn-" + tenantId;
        tenants.insert(tenantId, tenantName, enc.encode(token));
        var t = new RoutingConfig.TenantRouting();
        t.setPurposes(new HashMap<>());
        var rule = new RoutingConfig.Rule();
        rule.setProvider("anthropic");
        rule.setModel("claude-haiku-4-5");
        rule.setAllowOverride(false);
        t.getPurposes().put("summarize_cell", rule);
        t.setDefault(rule);
        routingConfig.getTenants().put(tenantName, t);
        stub.resetAll();
    }

    @Test void createTriggerLongPollDone() throws Exception {
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

        stub.script(StubLlmScripts.Turn.endTurn("{\"x\":\"yes\"}"));
        var triggerResp = mvc.perform(post("/agents/a/run")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"payload\":{}}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        var runId = mapper.readTree(triggerResp).path("run_id").asText();

        MvcResult poll = mvc.perform(get("/runs/" + runId + "?wait_seconds=15")
                .header("Authorization", "Bearer " + token))
                .andReturn();
        mvc.perform(asyncDispatch(poll))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done"))
                .andExpect(jsonPath("$.output.x").value("yes"));
    }
}
