package de.vesterion.vistierie.agent.runner;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.routing.RoutingRule;
import de.vesterion.vistierie.routing.RoutingRuleRepository;
import de.vesterion.vistierie.routing.RoutingResolver;
import de.vesterion.vistierie.runs.Run;
import de.vesterion.vistierie.runs.RunEventRecorder;
import de.vesterion.vistierie.runs.RunStore;
import de.vesterion.vistierie.tenants.TenantRepository;
import de.vesterion.vistierie.testsupport.OperationalBudgetFixtures;
import de.vesterion.vistierie.testsupport.StubLlmProvider;
import de.vesterion.vistierie.testsupport.StubLlmScripts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test-stub-llm")
class AgentRunnerSubmitResultTest extends PostgresTestBase {

    @Autowired AgentRunner runner;
    @Autowired AgentRepository agents;
    @Autowired TenantRepository tenants;
    @Autowired RunStore runStore;
    @Autowired StubLlmProvider stub;
    @Autowired ObjectMapper mapper;
    @Autowired RoutingRuleRepository routingRules;
    @Autowired RoutingResolver routingResolver;
    @Autowired OperationalBudgetFixtures budgetFixtures;
    @Autowired RunEventRecorder events;

    private UUID tenantId;

    @BeforeEach void up() {
        stub.resetAll();
        tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tn-" + tenantId, "h");
        var now = Instant.now();
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, null,
                "anthropic", "claude-haiku-4-5", 1000, false, false, now, now));
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, "summarize_cell",
                "anthropic", "claude-haiku-4-5", 500, false, false, now, now));
        routingResolver.bumpVersion();
    }

    private UUID givenAgent(JsonNode outputSchema) {
        return givenAgent(outputSchema, mapper.createArrayNode());
    }

    private UUID givenAgent(JsonNode outputSchema, JsonNode tools) {
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "ag-" + agentId, "you analyse", "summarize_cell",
                tools, outputSchema, 5, 60, "wt-tok",
                false, null, null, null, null, null, null);
        budgetFixtures.seed(tenantId, agentId);
        return agentId;
    }

    /** One synthetic HTTP tool of the agent's own — never dispatched, only offered. */
    private JsonNode ownTools() {
        var tools = mapper.createArrayNode();
        tools.add(mapper.valueToTree(Map.of(
                "name", "synth.lookup", "description", "s",
                "input_schema", Map.of("type", "object"),
                "webhook_url", "http://192.0.2.10/tools/synth.lookup")));
        return tools;
    }

    private List<String> lastToolNames() {
        var tools = stub.lastRequest().tools();
        return tools == null ? List.of() : tools.stream().map(t -> String.valueOf(t.get("name"))).toList();
    }

    private long countEvents(String runId, String type) {
        return events.byRun(runId).stream().filter(e -> type.equals(e.type())).count();
    }

    @Test void submitResultBlockEndsRunWithValidatedOutput() throws Exception {
        var schema = mapper.readTree("""
                {"type":"object","required":["verdicts"],
                 "properties":{"verdicts":{"type":"array"}}}
                """);
        var agentId = givenAgent(schema);

        stub.script(StubLlmScripts.Turn.toolUses(
                StubLlmScripts.Turn.toolUse(ResultToolFactory.TOOL_NAME,
                        Map.of("verdicts", List.of(Map.of("symbol", "SYNTH", "action", "HOLD"))))));

        var runId = runner.startRunSync(tenantId, agentId, "manual",
                mapper.readTree("{}"), null, null, null);

        Run r = runStore.get(runId);
        assertThat(r.status()).isEqualTo("done");
        assertThat(r.output().path("verdicts").get(0).path("symbol").asText()).isEqualTo("SYNTH");
        assertThat(r.error()).isNull();
    }

    @Test void submitResultBlockFailsRunWhenPayloadViolatesSchema() throws Exception {
        var schema = mapper.readTree("""
                {"type":"object","required":["verdicts"],
                 "properties":{"verdicts":{"type":"array"}}}
                """);
        var agentId = givenAgent(schema);

        stub.script(StubLlmScripts.Turn.toolUses(
                StubLlmScripts.Turn.toolUse(ResultToolFactory.TOOL_NAME, Map.of("other", "x"))));

        var runId = runner.startRunSync(tenantId, agentId, "manual",
                mapper.readTree("{}"), null, null, null);

        Run r = runStore.get(runId);
        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.error()).startsWith("output_schema: submit_result: ");
    }

    @Test void submitResultToolIsAppendedToTheAgentsOwnToolsWhenSchemaPresent() throws Exception {
        var withSchema = givenAgent(mapper.readTree("""
                {"type":"object"}
                """), ownTools());
        // submit_result, not plain end_turn text: an object-typed output_schema offers the
        // tool, and a bare end_turn now gets nudged instead of ending the run in one call —
        // scripting only one turn would drive the run into an uncaught IllegalStateException.
        stub.script(StubLlmScripts.Turn.toolUses(
                StubLlmScripts.Turn.toolUse(ResultToolFactory.TOOL_NAME, Map.of())));
        var withSchemaRunId = runner.startRunSync(tenantId, withSchema, "manual", mapper.readTree("{}"), null, null, null);
        // BOTH names: the tool is appended, never replaces the agent's own tools.
        assertThat(lastToolNames()).contains("synth.lookup", ResultToolFactory.TOOL_NAME);
        assertThat(runStore.get(withSchemaRunId).status()).isEqualTo("done");

        var withoutSchema = givenAgent(null, ownTools());
        stub.script(StubLlmScripts.Turn.endTurn("done"));
        runner.startRunSync(tenantId, withoutSchema, "manual", mapper.readTree("{}"), null, null, null);
        assertThat(lastToolNames()).contains("synth.lookup").doesNotContain(ResultToolFactory.TOOL_NAME);
    }

    @Test void submitResultToolIsNotOfferedForANonObjectSchema() throws Exception {
        // AgentDefinitionValidator accepts any meta-schema-valid schema, so {"type":"array"} is a
        // definition that exists today. The API rejects a non-object input_schema, so offering the
        // tool would 400 every turn — such an agent must keep the text path.
        var agentId = givenAgent(mapper.readTree("""
                {"type":"array"}
                """), ownTools());
        stub.script(StubLlmScripts.Turn.endTurn("[]"));
        runner.startRunSync(tenantId, agentId, "manual", mapper.readTree("{}"), null, null, null);
        assertThat(lastToolNames()).contains("synth.lookup").doesNotContain(ResultToolFactory.TOOL_NAME);
    }

    @Test void nudgesTwiceThenFallsBackToTextParsing() throws Exception {
        var schema = mapper.readTree("""
                {"type":"object","required":["verdicts"]}
                """);
        var agentId = givenAgent(schema);

        // Three end_turn responses without submit_result; the third carries parseable JSON.
        stub.script(
                StubLlmScripts.Turn.endTurn("Ich denke noch nach."),
                StubLlmScripts.Turn.endTurn("Immer noch am Rechnen."),
                StubLlmScripts.Turn.endTurn("""
                        {"verdicts":[]}
                        """));

        var runId = runner.startRunSync(tenantId, agentId, "manual",
                mapper.readTree("{}"), null, null, null);

        Run r = runStore.get(runId);
        assertThat(r.status()).isEqualTo("done");
        assertThat(r.output().has("verdicts")).isTrue();
        assertThat(countEvents(runId, "submit_result_nudged")).isEqualTo(2);
        assertThat(countEvents(runId, "submit_result_fallback")).isEqualTo(1);
    }

    @Test void fallbackStillFailsOnUnparseableText() throws Exception {
        var schema = mapper.readTree("""
                {"type":"object","required":["verdicts"]}
                """);
        var agentId = givenAgent(schema);

        stub.script(
                StubLlmScripts.Turn.endTurn("kein JSON"),
                StubLlmScripts.Turn.endTurn("weiterhin kein JSON"),
                StubLlmScripts.Turn.endTurn("immer noch keins"));

        var runId = runner.startRunSync(tenantId, agentId, "manual",
                mapper.readTree("{}"), null, null, null);

        Run r = runStore.get(runId);
        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.error()).startsWith("output_schema:");
    }

    @Test void fallbackStillRunsWhenNudgesWouldOtherwiseExhaustMaxTurns() throws Exception {
        // A tight max_turns is not hypothetical: the lowest configured value in prod is 4
        // (daywalker-deep, renfield). With max_turns=2 here, an unconditional nudge on turn 0
        // would leave no budget to fall back on turn 1 and the run would die with
        // max_turns_exceeded instead of ever parsing the model's text. The last available turn
        // must always fall back rather than nudge.
        var schema = mapper.readTree("""
                {"type":"object","required":["verdicts"]}
                """);
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "ag-" + agentId, "you analyse", "summarize_cell",
                mapper.createArrayNode(), schema, 2, 60, "wt-tok",
                false, null, null, null, null, null, null);
        budgetFixtures.seed(tenantId, agentId);

        stub.script(
                StubLlmScripts.Turn.endTurn("Ich denke noch nach."),
                StubLlmScripts.Turn.endTurn("""
                        {"verdicts":[]}
                        """));

        var runId = runner.startRunSync(tenantId, agentId, "manual",
                mapper.readTree("{}"), null, null, null);

        Run r = runStore.get(runId);
        assertThat(r.status()).isEqualTo("done");
        assertThat(r.output().has("verdicts")).isTrue();
        assertThat(countEvents(runId, "submit_result_nudged")).isEqualTo(1);
        assertThat(countEvents(runId, "submit_result_fallback")).isEqualTo(1);
    }
}
