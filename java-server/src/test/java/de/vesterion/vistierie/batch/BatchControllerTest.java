package de.vesterion.vistierie.batch;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.auth.AuthFilter;
import de.vesterion.vistierie.routing.RoutingRule;
import de.vesterion.vistierie.routing.RoutingRuleRepository;
import de.vesterion.vistierie.routing.RoutingResolver;
import de.vesterion.vistierie.tenants.TenantRepository;
import de.vesterion.vistierie.testsupport.OperationalBudgetFixtures;
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
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test-stub-llm")
class BatchControllerTest extends PostgresTestBase {

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
        stub.resetAll();
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
    }

    @Test
    void submitAcceptedReturnsBatchCreatedResponse() throws Exception {
        var schema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}},\"required\":[\"x\"]}");
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "summ", "p", "summarize_cell",
                mapper.createArrayNode(), schema, 3, 30, "wt", false, null, null, null);
        budgetFixtures.seed(tenantId, agentId);

        var body = """
                { "items": [
                    {"payload":{"cell":"c1"}},
                    {"payload":{"cell":"c2"}}
                  ]
                }
                """;
        mvc.perform(post("/agents/summ/batch")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("queued"))
                .andExpect(jsonPath("$.items_total").value(2))
                .andExpect(jsonPath("$.anthropic_batch_id").exists())
                .andExpect(jsonPath("$.agent_name").value("summ"));
    }

    @Test
    void rejectsAgentWithToolsAs400() throws Exception {
        var tools = mapper.createArrayNode();
        tools.add(mapper.valueToTree(Map.of(
                "name","cell.read","description","r","input_schema",Map.of("type","object"),
                "webhook_url","http://x/r")));
        var schema = mapper.readTree("{\"type\":\"object\"}");
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "with-tools", "p", "summarize_cell",
                tools, schema, 3, 30, "wt", false, null, null, null);
        budgetFixtures.seed(tenantId, agentId);

        mvc.perform(post("/agents/with-tools/batch")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"payload\":{}}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAgentWithoutOutputSchemaAs400() throws Exception {
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "no-schema", "p", "summarize_cell",
                mapper.createArrayNode(), null, 3, 30, "wt", false, null, null, null);
        budgetFixtures.seed(tenantId, agentId);

        mvc.perform(post("/agents/no-schema/batch")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"payload\":{}}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsPausedAgentAs409() throws Exception {
        var schema = mapper.readTree("{\"type\":\"object\"}");
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "paused", "p", "summarize_cell",
                mapper.createArrayNode(), schema, 3, 30, "wt", true, null, null, null);

        mvc.perform(post("/agents/paused/batch")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"payload\":{}}]}"))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsUnknownAgentAs404() throws Exception {
        mvc.perform(post("/agents/does-not-exist/batch")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"payload\":{}}]}"))
                .andExpect(status().isNotFound());
    }
}
