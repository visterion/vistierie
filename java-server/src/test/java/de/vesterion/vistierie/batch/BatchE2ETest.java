package de.vesterion.vistierie.batch;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.auth.AuthFilter;
import de.vesterion.vistierie.pricing.Usage;
import de.vesterion.vistierie.provider.BatchResult;
import de.vesterion.vistierie.routing.RoutingRule;
import de.vesterion.vistierie.routing.RoutingRuleRepository;
import de.vesterion.vistierie.routing.RoutingResolver;
import de.vesterion.vistierie.runs.RunRepository;
import de.vesterion.vistierie.tenants.TenantRepository;
import de.vesterion.vistierie.testsupport.OperationalBudgetFixtures;
import de.vesterion.vistierie.testsupport.StubLlmProvider;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test-stub-llm")
@TestPropertySource(properties = "vistierie.agents.batch.poll-millis=200")
class BatchE2ETest extends PostgresTestBase {

    @Autowired WebApplicationContext wac;
    @Autowired AuthFilter authFilter;
    @Autowired TenantRepository tenants;
    @Autowired AgentRepository agents;
    @Autowired RunRepository runs;
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
        // Drain any stale open-batch parent runs left by other test contexts in the shared DB.
        // Without this, the 200 ms scheduler may process a stale run whose anthropic_batch_id
        // collides with the fresh stub's counter-based IDs, consuming our results first.
        runs.findOpenBatchParents().forEach(r ->
                runs.markTerminal(r.id(), "failed", null, "test-cleanup", null));
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
    void postBatchPolledToCompletion() throws Exception {
        var schema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}},\"required\":[\"x\"]}");
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "summ-e2e", "p", "summarize_cell",
                mapper.createArrayNode(), schema, 3, 30, "wt", false, null);
        budgetFixtures.seed(tenantId, agentId);

        var resp = mvc.perform(post("/agents/summ-e2e/batch")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "items": [
                            {"payload":{"cell":"c1"}},
                            {"payload":{"cell":"c2"}},
                            {"payload":{"cell":"c3"}}
                          ]
                        }
                        """))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        var json = mapper.readTree(resp);
        var parentRunId = json.path("run_id").asText();
        var batchId = json.path("anthropic_batch_id").asText();

        // Stage results before the polling tick fires (the @Scheduled bean polls every 200 ms)
        var children = runs.findByParent(parentRunId);
        assertThat(children).hasSize(3);
        var c1 = children.get(0).id();
        var c2 = children.get(1).id();
        var c3 = children.get(2).id();

        var content = mapper.createArrayNode();
        content.add(mapper.createObjectNode().put("type","text").put("text","{\"x\":\"v1\"}"));
        var content2 = mapper.createArrayNode();
        content2.add(mapper.createObjectNode().put("type","text").put("text","{\"x\":\"v2\"}"));
        var content3 = mapper.createArrayNode();
        content3.add(mapper.createObjectNode().put("type","text").put("text","{\"x\":\"v3\"}"));
        stub.completeBatch(batchId, List.of(
                new BatchResult(c1, "succeeded", "{\"x\":\"v1\"}", "end_turn",
                        new Usage(10,5,0,0), "claude-haiku-4-5", content, null),
                new BatchResult(c2, "succeeded", "{\"x\":\"v2\"}", "end_turn",
                        new Usage(10,5,0,0), "claude-haiku-4-5", content2, null),
                new BatchResult(c3, "succeeded", "{\"x\":\"v3\"}", "end_turn",
                        new Usage(10,5,0,0), "claude-haiku-4-5", content3, null)));

        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() ->
                runs.findById(parentRunId)
                        .map(r -> "done".equals(r.status())).orElse(false));

        var parent = runs.findById(parentRunId).orElseThrow();
        assertThat(parent.output().path("items_done").asInt()).isEqualTo(3);
        assertThat(parent.output().path("items_failed").asInt()).isEqualTo(0);
        assertThat(runs.findByParent(parentRunId))
                .allSatisfy(c -> assertThat(c.status()).isEqualTo("done"));
    }
}
