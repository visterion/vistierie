package de.vesterion.vistierie.e2e;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agent.runner.AgentRunner;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.routing.RoutingRule;
import de.vesterion.vistierie.routing.RoutingRuleRepository;
import de.vesterion.vistierie.routing.RoutingResolver;
import de.vesterion.vistierie.runs.Run;
import de.vesterion.vistierie.runs.RunRepository;
import de.vesterion.vistierie.runs.RunStore;
import de.vesterion.vistierie.tenants.TenantRepository;
import de.vesterion.vistierie.testsupport.StubLlmProvider;
import de.vesterion.vistierie.testsupport.StubLlmScripts;
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

@ActiveProfiles("test-stub-llm")
class ParallelQueenWithBeesE2ETest extends PostgresTestBase {

    @Autowired AgentRunner runner;
    @Autowired RunStore runStore;
    @Autowired RunRepository runRepo;
    @Autowired AgentRepository agents;
    @Autowired TenantRepository tenants;
    @Autowired StubLlmProvider stub;
    @Autowired ObjectMapper mapper;
    @Autowired RoutingRuleRepository routingRules;
    @Autowired RoutingResolver routingResolver;

    @BeforeEach void resetStub() { stub.resetAll(); }

    @Test void queenDispatchesFiveBeesInOneTurnAndAggregates() throws Exception {
        var tenantId = UUID.randomUUID();
        var tenantName = "tn-" + tenantId;
        tenants.insert(tenantId, tenantName, "h");
        var now = Instant.now();
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, null,
                "anthropic", "claude-haiku-4-5", 1000, false, false, now, now));
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, "summarize_cell",
                "anthropic", "claude-haiku-4-5", 500, false, false, now, now));
        routingResolver.bumpVersion();

        var beeSchema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"finding\":{\"type\":\"string\"}},\"required\":[\"finding\"]}");
        var beeId = UUID.randomUUID();
        agents.insert(beeId, tenantId, "bee", "p", "summarize_cell",
                mapper.createArrayNode(), beeSchema, 3, 60, "wt", false, null);

        var queenTools = mapper.createArrayNode();
        queenTools.add(mapper.valueToTree(Map.of(
                "name", "dispatch_bee", "description", "x",
                "input_schema", Map.of("type","object"),
                "type", "subagent", "target_agent", "bee")));
        var queenSchema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"verdict\":{\"type\":\"string\"}},\"required\":[\"verdict\"]}");
        var queenId = UUID.randomUUID();
        agents.insert(queenId, tenantId, "queen", "p", "summarize_cell",
                queenTools, queenSchema, 5, 60, "wt", false, null);

        stub.scriptForAgent("queen",
                StubLlmScripts.Turn.toolUses(
                        StubLlmScripts.Turn.toolUse("dispatch_bee", Map.of("cell_id","c1")),
                        StubLlmScripts.Turn.toolUse("dispatch_bee", Map.of("cell_id","c2")),
                        StubLlmScripts.Turn.toolUse("dispatch_bee", Map.of("cell_id","c3")),
                        StubLlmScripts.Turn.toolUse("dispatch_bee", Map.of("cell_id","c4")),
                        StubLlmScripts.Turn.toolUse("dispatch_bee", Map.of("cell_id","c5"))),
                StubLlmScripts.Turn.endTurn("{\"verdict\":\"5 cells curated\"}"));

        stub.scriptForAgent("bee",
                StubLlmScripts.Turn.endTurn("{\"finding\":\"f1\"}"),
                StubLlmScripts.Turn.endTurn("{\"finding\":\"f2\"}"),
                StubLlmScripts.Turn.endTurn("{\"finding\":\"f3\"}"),
                StubLlmScripts.Turn.endTurn("{\"finding\":\"f4\"}"),
                StubLlmScripts.Turn.endTurn("{\"finding\":\"f5\"}"));

        var queenRunId = runner.startRunSync(tenantId, queenId, "manual",
                mapper.readTree("{}"), null, null, null);

        Run queen = runStore.get(queenRunId);
        assertThat(queen.status()).isEqualTo("done");
        assertThat(queen.output().path("verdict").asText()).isEqualTo("5 cells curated");

        List<Run> bees = runRepo.findByParent(queenRunId);
        assertThat(bees).hasSize(5);
        for (Run b : bees) {
            assertThat(b.status()).isEqualTo("done");
            assertThat(b.parentRunId()).isEqualTo(queenRunId);
            assertThat(b.output().path("finding").asText()).startsWith("f");
        }

        var queenMessages = queen.messagesSnapshot().toString();
        for (int i = 1; i <= 5; i++) assertThat(queenMessages).contains("\"f" + i + "\"");
    }
}
