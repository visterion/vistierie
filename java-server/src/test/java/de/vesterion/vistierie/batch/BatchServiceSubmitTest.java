package de.vesterion.vistierie.batch;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.budget.AgentBudgetRepository;
import de.vesterion.vistierie.budget.TenantBudgetRepository;
import de.vesterion.vistierie.budget.admin.dto.BudgetPatchRequest;
import de.vesterion.vistierie.routing.RoutingRule;
import de.vesterion.vistierie.routing.RoutingRuleRepository;
import de.vesterion.vistierie.routing.RoutingResolver;
import de.vesterion.vistierie.runs.RunRepository;
import de.vesterion.vistierie.tenants.TenantRepository;
import de.vesterion.vistierie.testsupport.StubLlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("test-stub-llm")
class BatchServiceSubmitTest extends PostgresTestBase {

    @Autowired BatchService batchService;
    @Autowired AgentRepository agents;
    @Autowired TenantBudgetRepository tenantBudgets;
    @Autowired AgentBudgetRepository agentBudgets;
    @Autowired RunRepository runs;
    @Autowired TenantRepository tenants;
    @Autowired StubLlmProvider stub;
    @Autowired ObjectMapper mapper;
    @Autowired RoutingRuleRepository routingRules;
    @Autowired RoutingResolver routingResolver;

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
        tenantBudgets.patch(tenantId, new BudgetPatchRequest(10_000L, 100_000L, 80, 90));
    }

    @Test
    void submitCreatesParentAndChildren() throws Exception {
        var schema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}},\"required\":[\"x\"]}");
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "summarize", "p", "summarize_cell",
                mapper.createArrayNode(), schema, 3, 30, "wt", false, null, null, null);
        agentBudgets.patch(agentId, new BudgetPatchRequest(5_000L, 50_000L, 80, 90));
        var agent = agents.findById(agentId).orElseThrow();

        var items = List.of(
                new BatchItemRequest(null, mapper.readTree("{\"cell\":\"c1\"}")),
                new BatchItemRequest(null, mapper.readTree("{\"cell\":\"c2\"}")),
                new BatchItemRequest(null, mapper.readTree("{\"cell\":\"c3\"}")));

        var res = batchService.submit(tenantId, tenantName, agent, items, null, null);

        assertThat(res.itemCount()).isEqualTo(3);
        assertThat(res.anthropicBatchId()).startsWith("stubbatch_");

        var parent = runs.findById(res.parentRunId()).orElseThrow();
        assertThat(parent.trigger()).isEqualTo("batch");
        assertThat(parent.anthropicBatchId()).isEqualTo(res.anthropicBatchId());

        var children = runs.findByParent(res.parentRunId());
        assertThat(children).hasSize(3);
        assertThat(children).allSatisfy(c -> assertThat(c.trigger()).isEqualTo("batch_item"));

        // The stub recorded the submitted items
        var submitted = stub.lastSubmittedBatch();
        assertThat(submitted).hasSize(3);
        assertThat(submitted.get(0).request().model()).isEqualTo("claude-haiku-4-5");
        assertThat(submitted.get(0).request().system()).isEqualTo("p");
    }

    @Test
    void rejectsAgentWithTools() throws Exception {
        var tools = mapper.createArrayNode();
        tools.add(mapper.valueToTree(Map.of(
                "name","cell.read","description","r","input_schema",Map.of("type","object"),
                "webhook_url","http://x/r")));
        var agentId = UUID.randomUUID();
        var schema = mapper.readTree("{\"type\":\"object\"}");
        agents.insert(agentId, tenantId, "with-tools", "p", "summarize_cell",
                tools, schema, 3, 30, "wt", false, null, null, null);
        agentBudgets.patch(agentId, new BudgetPatchRequest(5_000L, 50_000L, 80, 90));
        var agent = agents.findById(agentId).orElseThrow();
        assertThatThrownBy(() -> batchService.submit(tenantId, tenantName, agent,
                List.of(new BatchItemRequest(null, mapper.createObjectNode())), null, null))
                .isInstanceOf(BatchService.BadBatchException.class)
                .hasMessageContaining("tools");
    }

    @Test
    void rejectsAgentWithoutOutputSchema() throws Exception {
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "no-schema", "p", "summarize_cell",
                mapper.createArrayNode(), null, 3, 30, "wt", false, null, null, null);
        agentBudgets.patch(agentId, new BudgetPatchRequest(5_000L, 50_000L, 80, 90));
        var agent = agents.findById(agentId).orElseThrow();
        assertThatThrownBy(() -> batchService.submit(tenantId, tenantName, agent,
                List.of(new BatchItemRequest(null, mapper.createObjectNode())), null, null))
                .isInstanceOf(BatchService.BadBatchException.class)
                .hasMessageContaining("output_schema");
    }

    @Test
    void rejectsDuplicateCustomId() throws Exception {
        var schema = mapper.readTree("{\"type\":\"object\"}");
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "summ2", "p", "summarize_cell",
                mapper.createArrayNode(), schema, 3, 30, "wt", false, null, null, null);
        agentBudgets.patch(agentId, new BudgetPatchRequest(5_000L, 50_000L, 80, 90));
        var agent = agents.findById(agentId).orElseThrow();
        var items = List.of(
                new BatchItemRequest("ABC", mapper.createObjectNode()),
                new BatchItemRequest("ABC", mapper.createObjectNode()));
        assertThatThrownBy(() -> batchService.submit(tenantId, tenantName, agent, items, null, null))
                .isInstanceOf(BatchService.BadBatchException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void rejectsInvalidCustomIdRegex() throws Exception {
        var schema = mapper.readTree("{\"type\":\"object\"}");
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "summ3", "p", "summarize_cell",
                mapper.createArrayNode(), schema, 3, 30, "wt", false, null, null, null);
        agentBudgets.patch(agentId, new BudgetPatchRequest(5_000L, 50_000L, 80, 90));
        var agent = agents.findById(agentId).orElseThrow();
        var items = List.of(new BatchItemRequest("has spaces!", mapper.createObjectNode()));
        assertThatThrownBy(() -> batchService.submit(tenantId, tenantName, agent, items, null, null))
                .isInstanceOf(BatchService.BadBatchException.class)
                .hasMessageContaining("custom_id");
    }

    @Test
    void batchSubmitRejectsAgentWithoutBudget() throws Exception {
        var schema = mapper.readTree("{\"type\":\"object\"}");
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "no-budget", "p", "summarize_cell",
                mapper.createArrayNode(), schema, 3, 30, "wt", false, null, null, null);
        var agent = agents.findById(agentId).orElseThrow();

        assertThatThrownBy(() -> batchService.submit(tenantId, tenantName, agent,
                List.of(new BatchItemRequest(null, mapper.createObjectNode())), null, null))
                .isInstanceOf(BatchService.BadBatchException.class)
                .hasMessageContaining("budget");
    }
}
