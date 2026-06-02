package de.vesterion.vistierie.agent.runner;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.routing.RoutingRule;
import de.vesterion.vistierie.routing.RoutingRuleRepository;
import de.vesterion.vistierie.routing.RoutingResolver;
import de.vesterion.vistierie.runs.Run;
import de.vesterion.vistierie.runs.RunRepository;
import de.vesterion.vistierie.runs.RunStore;
import de.vesterion.vistierie.tenants.TenantRepository;
import de.vesterion.vistierie.testsupport.OperationalBudgetFixtures;
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
class AgentRunnerSubagentTest extends PostgresTestBase {

    @Autowired AgentRunner runner;
    @Autowired AgentRepository agents;
    @Autowired TenantRepository tenants;
    @Autowired RunStore runStore;
    @Autowired RunRepository runRepo;
    @Autowired StubLlmProvider stub;
    @Autowired ObjectMapper mapper;
    @Autowired RoutingRuleRepository routingRules;
    @Autowired RoutingResolver routingResolver;
    @Autowired OperationalBudgetFixtures budgetFixtures;

    @BeforeEach void resetStub() { stub.resetAll(); }

    private void registerRouting(UUID tId) {
        var now = Instant.now();
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tId, null, null,
                "anthropic", "claude-haiku-4-5", 1000, false, false, now, now));
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tId, null, "summarize_cell",
                "anthropic", "claude-haiku-4-5", 500, false, false, now, now));
        routingResolver.bumpVersion();
    }

    @Test void subagentOutputShieldedFromParent() throws Exception {
        var tenantId = UUID.randomUUID();
        var tenantName = "tn-" + tenantId;
        tenants.insert(tenantId, tenantName, "h");
        registerRouting(tenantId);

        var beeSchema = mapper.readTree("""
                {"type":"object","properties":{"finding":{"type":"string"}},"required":["finding"]}
                """);
        var beeId = UUID.randomUUID();
        agents.insert(beeId, tenantId, "bee", "you are bee", "summarize_cell",
                mapper.createArrayNode(), beeSchema, 5, 60, "wt", false, null, null, null, null, null, null);
        budgetFixtures.seed(tenantId, beeId);

        var queenTools = mapper.createArrayNode();
        queenTools.add(mapper.valueToTree(Map.of(
                "name","dispatch_bee","description","go bee",
                "input_schema", Map.of("type","object"),
                "type","subagent","target_agent","bee")));
        var queenId = UUID.randomUUID();
        var queenSchema = mapper.readTree("""
                {"type":"object","properties":{"verdict":{"type":"string"}},"required":["verdict"]}
                """);
        agents.insert(queenId, tenantId, "queen", "you are queen", "summarize_cell",
                queenTools, queenSchema, 5, 60, "wt", false, null, null, null, null, null, null);
        budgetFixtures.seed(tenantId, queenId);

        stub.scriptForAgent("queen",
                StubLlmScripts.Turn.toolUses(
                        StubLlmScripts.Turn.toolUse("dispatch_bee", Map.of("cell_id","c1"))),
                StubLlmScripts.Turn.endTurn("{\"verdict\":\"shipped\"}"));
        stub.scriptForAgent("bee",
                StubLlmScripts.Turn.endTurn("{\"finding\":\"interesting cell\"}"));

        var queenRunId = runner.startRunSync(tenantId, queenId, "manual",
                mapper.readTree("{}"), null, null, null);

        Run queenRun = runStore.get(queenRunId);
        assertThat(queenRun.status()).isEqualTo("done");
        assertThat(queenRun.output().path("verdict").asText()).isEqualTo("shipped");

        List<Run> children = runRepo.findByParent(queenRunId);
        assertThat(children).hasSize(1);
        assertThat(children.get(0).output().path("finding").asText()).isEqualTo("interesting cell");

        var queenMessages = queenRun.messagesSnapshot().toString();
        assertThat(queenMessages).contains("interesting cell");
        assertThat(queenMessages).doesNotContain("you are bee");
    }

    @Test void subagentSchemaViolationFailsParent() throws Exception {
        var tenantId = UUID.randomUUID();
        var tenantName = "tn-" + tenantId;
        tenants.insert(tenantId, tenantName, "h");
        registerRouting(tenantId);

        var beeSchema = mapper.readTree("""
                {"type":"object","properties":{"finding":{"type":"string"}},"required":["finding"]}""");
        var beeId = UUID.randomUUID();
        agents.insert(beeId, tenantId, "bee", "p", "summarize_cell",
                mapper.createArrayNode(), beeSchema, 3, 60, "wt", false, null, null, null, null, null, null);
        budgetFixtures.seed(tenantId, beeId);
        var queenTools = mapper.createArrayNode();
        queenTools.add(mapper.valueToTree(Map.of(
                "name","dispatch_bee","description","go","input_schema",Map.of("type","object"),
                "type","subagent","target_agent","bee")));
        var queenId = UUID.randomUUID();
        agents.insert(queenId, tenantId, "queen", "p", "summarize_cell",
                queenTools, null, 3, 60, "wt", false, null, null, null, null, null, null);
        budgetFixtures.seed(tenantId, queenId);

        stub.scriptForAgent("queen",
                StubLlmScripts.Turn.toolUses(StubLlmScripts.Turn.toolUse("dispatch_bee", Map.of())));
        stub.scriptForAgent("bee", StubLlmScripts.Turn.endTurn("not even json"));

        var runId = runner.startRunSync(tenantId, queenId, "manual",
                mapper.readTree("{}"), null, null, null);
        Run r = runStore.get(runId);
        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.error()).contains("tool_error");
    }
}
