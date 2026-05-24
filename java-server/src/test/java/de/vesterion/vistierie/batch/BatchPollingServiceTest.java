package de.vesterion.vistierie.batch;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.pricing.Usage;
import de.vesterion.vistierie.provider.BatchResult;
import de.vesterion.vistierie.routing.RoutingRule;
import de.vesterion.vistierie.routing.RoutingRuleRepository;
import de.vesterion.vistierie.routing.RoutingResolver;
import de.vesterion.vistierie.runs.RunRepository;
import de.vesterion.vistierie.tenants.TenantRepository;
import de.vesterion.vistierie.testsupport.OperationalBudgetFixtures;
import de.vesterion.vistierie.testsupport.StubLlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test-stub-llm")
class BatchPollingServiceTest extends PostgresTestBase {

    @Autowired BatchPollingService polling;
    @Autowired BatchService batchService;
    @Autowired AgentRepository agents;
    @Autowired RunRepository runs;
    @Autowired TenantRepository tenants;
    @Autowired StubLlmProvider stub;
    @Autowired ObjectMapper mapper;
    @Autowired RoutingRuleRepository routingRules;
    @Autowired RoutingResolver routingResolver;
    @Autowired OperationalBudgetFixtures budgetFixtures;

    UUID tenantId;
    String tenantName;

    @BeforeEach void up() {
        stub.resetAll();
        tenantId = UUID.randomUUID();
        tenantName = "tn-" + tenantId;
        tenants.insert(tenantId, tenantName, "h");
        var now = Instant.now();
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, null,
                "anthropic", "claude-haiku-4-5", 1000, false, false, now, now));
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, "summarize_cell",
                "anthropic", "claude-haiku-4-5", 500, false, false, now, now));
        routingResolver.bumpVersion();
    }

    @Test
    void tickTransitionsEndedBatchToDone() throws Exception {
        var schema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}},\"required\":[\"x\"]}");
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "summ", "p", "summarize_cell",
                mapper.createArrayNode(), schema, 3, 30, "wt", false, null, null, null);
        budgetFixtures.seed(tenantId, agentId);
        var agent = agents.findById(agentId).orElseThrow();

        var items = List.of(
                new BatchItemRequest(null, mapper.readTree("{\"cell\":\"c1\"}")),
                new BatchItemRequest(null, mapper.readTree("{\"cell\":\"c2\"}")));
        var sub = batchService.submit(tenantId, tenantName, agent, items, null, null);

        // Tick before the stub batch is "ended" — nothing changes
        polling.tick();
        assertThat(runs.findById(sub.parentRunId()).orElseThrow().status()).isEqualTo("running");

        // Complete the stub batch
        var children = runs.findByParent(sub.parentRunId());
        var c1 = children.get(0).id();
        var c2 = children.get(1).id();
        var content = mapper.createArrayNode();
        content.add(mapper.createObjectNode().put("type","text").put("text","{\"x\":\"v1\"}"));
        var content2 = mapper.createArrayNode();
        content2.add(mapper.createObjectNode().put("type","text").put("text","{\"x\":\"v2\"}"));
        stub.completeBatch(sub.anthropicBatchId(), List.of(
                new BatchResult(c1, "succeeded", "{\"x\":\"v1\"}", "end_turn",
                        new Usage(10,5,0,0), "claude-haiku-4-5", content, null),
                new BatchResult(c2, "succeeded", "{\"x\":\"v2\"}", "end_turn",
                        new Usage(10,5,0,0), "claude-haiku-4-5", content2, null)));

        // Tick once — batch should be finalized
        polling.tick();

        var parent = runs.findById(sub.parentRunId()).orElseThrow();
        assertThat(parent.status()).isEqualTo("done");
        assertThat(parent.output().path("items_done").asInt()).isEqualTo(2);
        assertThat(parent.output().path("items_failed").asInt()).isEqualTo(0);
    }

    @Test
    void killSwitchTerminatesBatchOnTick() throws Exception {
        var schema = mapper.readTree("{\"type\":\"object\"}");
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "summ", "p", "summarize_cell",
                mapper.createArrayNode(), schema, 3, 30, "wt", false, null, null, null);
        budgetFixtures.seed(tenantId, agentId);
        var agent = agents.findById(agentId).orElseThrow();
        var items = List.of(new BatchItemRequest(null, mapper.createObjectNode()));
        var sub = batchService.submit(tenantId, tenantName, agent, items, null, null);

        // Kill the tenant
        tenants.setKill(tenantId,
                Instant.now().plusSeconds(3600), "drill", "operator");

        polling.tick();

        var parent = runs.findById(sub.parentRunId()).orElseThrow();
        assertThat(parent.status()).isEqualTo("failed");
        assertThat(parent.error()).contains("killed");

        var children = runs.findByParent(sub.parentRunId());
        assertThat(children).allSatisfy(c -> assertThat(c.status()).isEqualTo("failed"));
    }
}
