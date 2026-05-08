package de.vesterion.vistierie.batch;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.pricing.Usage;
import de.vesterion.vistierie.provider.BatchResult;
import de.vesterion.vistierie.routing.RoutingConfig;
import de.vesterion.vistierie.runs.RunRepository;
import de.vesterion.vistierie.tenants.TenantRepository;
import de.vesterion.vistierie.testsupport.StubLlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test-stub-llm")
class BatchServiceFinalizeTest extends PostgresTestBase {

    @Autowired BatchService batchService;
    @Autowired AgentRepository agents;
    @Autowired RunRepository runs;
    @Autowired TenantRepository tenants;
    @Autowired StubLlmProvider stub;
    @Autowired ObjectMapper mapper;
    @Autowired RoutingConfig routingConfig;
    @Autowired JdbcClient jdbc;

    UUID tenantId;
    String tenantName;

    @BeforeEach void up() {
        stub.resetAll();
        tenantId = UUID.randomUUID();
        tenantName = "tn-" + tenantId;
        tenants.insert(tenantId, tenantName, "h");
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

    @Test
    void finalizeMixedResultsUpdatesParentAndChildren() throws Exception {
        var schema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}},\"required\":[\"x\"]}");
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "summ", "p", "summarize_cell",
                mapper.createArrayNode(), schema, 3, 30, "wt", false, null);
        var agent = agents.findById(agentId).orElseThrow();

        var items = List.of(
                new BatchItemRequest(null, mapper.readTree("{\"cell\":\"c1\"}")),
                new BatchItemRequest(null, mapper.readTree("{\"cell\":\"c2\"}")),
                new BatchItemRequest(null, mapper.readTree("{\"cell\":\"c3\"}")));
        var sub = batchService.submit(tenantId, tenantName, agent, items, null, null);
        var parentRunId = sub.parentRunId();
        var batchId = sub.anthropicBatchId();

        // Capture child ids in a deterministic way (any 3 children, by id)
        var children = runs.findByParent(parentRunId);
        assertThat(children).hasSize(3);
        var c1 = children.get(0).id();
        var c2 = children.get(1).id();
        var c3 = children.get(2).id();

        var content = mapper.createArrayNode();
        content.add(mapper.createObjectNode().put("type","text").put("text","{\"x\":\"hello\"}"));
        var succeeded = new BatchResult(c1, "succeeded", "{\"x\":\"hello\"}", "end_turn",
                new Usage(10, 5, 0, 0), "claude-haiku-4-5", content, null);
        var errored = new BatchResult(c2, "errored", null, null, null, null, null, "rate limited");
        var expired = new BatchResult(c3, "expired", null, null, null, null, null, null);

        batchService.finalize(parentRunId, batchId, Stream.of(succeeded, errored, expired));

        var parent = runs.findById(parentRunId).orElseThrow();
        assertThat(parent.status()).isEqualTo("done");
        assertThat(parent.output().path("items_total").asInt()).isEqualTo(3);
        assertThat(parent.output().path("items_done").asInt()).isEqualTo(1);
        assertThat(parent.output().path("items_failed").asInt()).isEqualTo(2);

        var c1Run = runs.findById(c1).orElseThrow();
        assertThat(c1Run.status()).isEqualTo("done");
        assertThat(c1Run.output().path("x").asText()).isEqualTo("hello");

        var c2Run = runs.findById(c2).orElseThrow();
        assertThat(c2Run.status()).isEqualTo("failed");
        assertThat(c2Run.error()).contains("rate limited");

        var c3Run = runs.findById(c3).orElseThrow();
        assertThat(c3Run.status()).isEqualTo("failed");
        assertThat(c3Run.error()).contains("expired");

        // One llm_calls row for the succeeded item, with batch_id and half-price cost
        var llmCount = jdbc.sql("SELECT count(*) FROM vistierie.llm_calls WHERE batch_id = ?")
                .param(batchId).query(Integer.class).single();
        assertThat(llmCount).isEqualTo(1);
    }

    @Test
    void finalizeSucceededButSchemaViolationFailsItem() throws Exception {
        var schema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}},\"required\":[\"x\"]}");
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "summ", "p", "summarize_cell",
                mapper.createArrayNode(), schema, 3, 30, "wt", false, null);
        var agent = agents.findById(agentId).orElseThrow();

        var items = List.of(new BatchItemRequest(null, mapper.readTree("{\"cell\":\"c1\"}")));
        var sub = batchService.submit(tenantId, tenantName, agent, items, null, null);
        var children = runs.findByParent(sub.parentRunId());
        var c1 = children.get(0).id();

        // Anthropic returns valid response, but the text is not valid JSON for our schema
        var content = mapper.createArrayNode();
        content.add(mapper.createObjectNode().put("type","text").put("text","not even json"));
        var bad = new BatchResult(c1, "succeeded", "not even json", "end_turn",
                new Usage(10, 5, 0, 0), "claude-haiku-4-5", content, null);

        batchService.finalize(sub.parentRunId(), sub.anthropicBatchId(), Stream.of(bad));

        var c1Run = runs.findById(c1).orElseThrow();
        assertThat(c1Run.status()).isEqualTo("failed");
        assertThat(c1Run.error()).contains("output_schema");

        var parent = runs.findById(sub.parentRunId()).orElseThrow();
        assertThat(parent.status()).isEqualTo("done");
        assertThat(parent.output().path("items_done").asInt()).isEqualTo(0);
        assertThat(parent.output().path("items_failed").asInt()).isEqualTo(1);
    }
}
