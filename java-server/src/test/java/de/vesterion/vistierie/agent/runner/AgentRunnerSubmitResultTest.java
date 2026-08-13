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

    // A single schema-violating submit_result no longer fails the run outright — see
    // schemaViolatingSubmitResultGetsAFollowUpInsteadOfDyingAtOnce and
    // schemaViolatingSubmitResultStillFailsOnceTheFollowUpBudgetIsSpent below.

    @Test void submitResultToolIsAppendedToTheAgentsOwnToolsWhenSchemaPresent() throws Exception {
        var withSchema = givenAgent(mapper.readTree("""
                {"type":"object"}
                """), ownTools());
        // submit_result rather than plain end_turn text: this asserts the tool reaches the
        // provider request, which only a turn that actually uses it can demonstrate.
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

    @Test void parseableTextEndsTheRunWithoutAnyFollowUp() throws Exception {
        // Parse FIRST. No model calls submit_result on day one, so a follow-up before the parse
        // would fire on every run of every schema-bearing agent — and on the subscription path
        // each follow-up is a full history replay. A healthy run must cost exactly one turn.
        var schema = mapper.readTree("""
                {"type":"object","required":["verdicts"]}
                """);
        var agentId = givenAgent(schema);

        stub.script(StubLlmScripts.Turn.endTurn("""
                {"verdicts":[]}
                """));

        var runId = runner.startRunSync(tenantId, agentId, "manual",
                mapper.readTree("{}"), null, null, null);

        Run r = runStore.get(runId);
        assertThat(r.status()).isEqualTo("done");
        assertThat(r.output().has("verdicts")).isTrue();
        assertThat(countEvents(runId, "submit_result_nudged")).isZero();
        assertThat(countEvents(runId, "submit_result_fallback")).isEqualTo(1);
    }

    @Test void followUpCarriesRealAssistantContentWhenTheTurnHasNoContentBlocks() throws Exception {
        // The primary provider returns NO content_blocks on end_turn. Setting that null onto the
        // follow-up's assistant message made the bridge drop the message and Bedrock reject it —
        // the model was asked to re-deliver a result no longer in its context.
        var schema = mapper.readTree("""
                {"type":"object","required":["verdicts"]}
                """);
        var agentId = givenAgent(schema);

        stub.script(
                StubLlmScripts.Turn.endTurn("Ich denke noch nach."),
                StubLlmScripts.Turn.endTurn("""
                        {"verdicts":[]}
                        """));

        var runId = runner.startRunSync(tenantId, agentId, "manual",
                mapper.readTree("{}"), null, null, null);

        assertThat(runStore.get(runId).status()).isEqualTo("done");
        // The last request carries the follow-up exchange: assistant turn, then the ask.
        var sent = stub.lastRequest().messages();
        var assistantMsgs = sent.stream().filter(m -> "assistant".equals(m.get("role"))).toList();
        assertThat(assistantMsgs).hasSize(1);
        var content = assistantMsgs.get(0).get("content");
        assertThat(content).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        var blocks = (List<Map<String, Object>>) content;
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).get("type")).isEqualTo("text");
        assertThat(String.valueOf(blocks.get(0).get("text"))).contains("Ich denke noch nach.");
    }

    @Test void followUpIsOmittedRatherThanEmptyWhenTheTurnHasNoContentAtAll() throws Exception {
        var schema = mapper.readTree("""
                {"type":"object","required":["verdicts"]}
                """);
        var agentId = givenAgent(schema);

        stub.script(
                StubLlmScripts.Turn.endTurn("   "),
                StubLlmScripts.Turn.endTurn("""
                        {"verdicts":[]}
                        """));

        var runId = runner.startRunSync(tenantId, agentId, "manual",
                mapper.readTree("{}"), null, null, null);

        assertThat(runStore.get(runId).status()).isEqualTo("done");
        var sent = stub.lastRequest().messages();
        // No assistant message at all — never one with null or empty content.
        assertThat(sent.stream().filter(m -> "assistant".equals(m.get("role"))).toList()).isEmpty();
        assertThat(sent).allSatisfy(m -> assertThat(m.get("content")).isNotNull());
    }

    @Test void schemaViolatingSubmitResultGetsAFollowUpInsteadOfDyingAtOnce() throws Exception {
        // The tool path must not be harsher than the text path it replaces: hand the validation
        // errors back as a tool_result and let the model correct itself.
        var schema = mapper.readTree("""
                {"type":"object","required":["verdicts"],
                 "properties":{"verdicts":{"type":"array"}}}
                """);
        var agentId = givenAgent(schema);

        stub.script(
                StubLlmScripts.Turn.toolUses(
                        StubLlmScripts.Turn.toolUse(ResultToolFactory.TOOL_NAME, Map.of("other", "x"))),
                StubLlmScripts.Turn.toolUses(
                        StubLlmScripts.Turn.toolUse(ResultToolFactory.TOOL_NAME,
                                Map.of("verdicts", List.of()))));

        var runId = runner.startRunSync(tenantId, agentId, "manual",
                mapper.readTree("{}"), null, null, null);

        Run r = runStore.get(runId);
        assertThat(r.status()).isEqualTo("done");
        assertThat(r.output().has("verdicts")).isTrue();
        assertThat(countEvents(runId, "submit_result_nudged")).isEqualTo(1);
        assertThat(countEvents(runId, "submit_result_received")).isEqualTo(1);

        // The rejected turn's only block got a matching tool_result. This turn has no siblings —
        // the fan-out over sibling blocks is covered by
        // schemaViolatingSubmitResultAlsoAnswersTheSiblingToolUseBlocksOfItsTurn below.
        var sent = stub.lastRequest().messages();
        @SuppressWarnings("unchecked")
        var results = (List<Map<String, Object>>) sent.get(sent.size() - 1).get("content");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("type")).isEqualTo("tool_result");
        assertThat(results.get(0).get("tool_use_id")).isNotNull();
        assertThat(String.valueOf(results.get(0).get("content"))).contains("submit_result");
    }

    @Test void schemaViolatingSubmitResultAlsoAnswersTheSiblingToolUseBlocksOfItsTurn() throws Exception {
        // A turn may carry submit_result NEXT TO real tool_use blocks. Intercepting submit_result
        // consumes the whole turn, so the siblings are never dispatched — but an unanswered
        // tool_use block is a provider-level 400 on the follow-up request. Every block of the
        // turn must come back with a tool_result, and the undispatched siblings must be flagged
        // as errors rather than passed off as successful tool executions.
        var schema = mapper.readTree("""
                {"type":"object","required":["verdicts"],
                 "properties":{"verdicts":{"type":"array"}}}
                """);
        var agentId = givenAgent(schema, ownTools());

        var sibling = StubLlmScripts.Turn.toolUse("synth.lookup", Map.of("q", "SYNTH"));
        var badSubmit = StubLlmScripts.Turn.toolUse(ResultToolFactory.TOOL_NAME, Map.of("other", "x"));
        stub.script(
                StubLlmScripts.Turn.toolUses(sibling, badSubmit),
                StubLlmScripts.Turn.toolUses(
                        StubLlmScripts.Turn.toolUse(ResultToolFactory.TOOL_NAME,
                                Map.of("verdicts", List.of()))));

        var runId = runner.startRunSync(tenantId, agentId, "manual",
                mapper.readTree("{}"), null, null, null);

        assertThat(runStore.get(runId).status()).isEqualTo("done");

        var sent = stub.lastRequest().messages();
        @SuppressWarnings("unchecked")
        var results = (List<Map<String, Object>>) sent.get(sent.size() - 1).get("content");
        assertThat(results).hasSize(2);
        assertThat(results).allSatisfy(b -> assertThat(b.get("type")).isEqualTo("tool_result"));
        assertThat(results.stream().map(b -> String.valueOf(b.get("tool_use_id"))).toList())
                .containsExactlyInAnyOrder(sibling.id(), badSubmit.id());

        var siblingResult = results.stream()
                .filter(b -> sibling.id().equals(b.get("tool_use_id"))).findFirst().orElseThrow();
        assertThat(siblingResult.get("is_error")).isEqualTo(true);
        assertThat(String.valueOf(siblingResult.get("content"))).contains("not executed");

        var submitResult = results.stream()
                .filter(b -> badSubmit.id().equals(b.get("tool_use_id"))).findFirst().orElseThrow();
        assertThat(submitResult.get("is_error")).isEqualTo(true);
        assertThat(String.valueOf(submitResult.get("content"))).contains("submit_result");
        // The sibling never reached the dispatcher, so no tool call was made for it.
        assertThat(countEvents(runId, "tool_dispatched")).isZero();
    }

    @Test void schemaViolatingSubmitResultStillFailsOnceTheFollowUpBudgetIsSpent() throws Exception {
        var schema = mapper.readTree("""
                {"type":"object","required":["verdicts"],
                 "properties":{"verdicts":{"type":"array"}}}
                """);
        var agentId = givenAgent(schema);

        stub.script(
                StubLlmScripts.Turn.toolUses(
                        StubLlmScripts.Turn.toolUse(ResultToolFactory.TOOL_NAME, Map.of("other", "1"))),
                StubLlmScripts.Turn.toolUses(
                        StubLlmScripts.Turn.toolUse(ResultToolFactory.TOOL_NAME, Map.of("other", "2"))),
                StubLlmScripts.Turn.toolUses(
                        StubLlmScripts.Turn.toolUse(ResultToolFactory.TOOL_NAME, Map.of("other", "3"))));

        var runId = runner.startRunSync(tenantId, agentId, "manual",
                mapper.readTree("{}"), null, null, null);

        Run r = runStore.get(runId);
        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.error()).startsWith("output_schema: submit_result: ");
        assertThat(countEvents(runId, "submit_result_nudged")).isEqualTo(2);
    }

    @Test void anAgentOwningTheToolNameKeepsItsOwnToolAndTheTextPath() throws Exception {
        // A definition registered BEFORE the reserved-name validator. Appending ours would send
        // two same-named tools in one request; such an agent keeps its old behaviour exactly.
        var tools = mapper.createArrayNode();
        tools.add(mapper.valueToTree(Map.of(
                "name", ResultToolFactory.TOOL_NAME, "description", "legacy operator tool",
                "input_schema", Map.of("type", "object"),
                "webhook_url", "http://192.0.2.10/tools/submit")));
        var agentId = givenAgent(mapper.readTree("""
                {"type":"object","required":["verdicts"]}
                """), tools);

        stub.script(StubLlmScripts.Turn.endTurn("""
                {"verdicts":[]}
                """));
        var runId = runner.startRunSync(tenantId, agentId, "manual",
                mapper.readTree("{}"), null, null, null);

        assertThat(lastToolNames().stream().filter(ResultToolFactory.TOOL_NAME::equals).count())
                .isEqualTo(1);
        assertThat(stub.lastRequest().tools().stream()
                .filter(t -> ResultToolFactory.TOOL_NAME.equals(t.get("name")))
                .findFirst().orElseThrow())
                .containsKey("webhook_url");
        assertThat(runStore.get(runId).status()).isEqualTo("done");
        assertThat(countEvents(runId, "submit_result_fallback")).isZero();
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

    /** An agent with a deliberately tight turn budget — prod's lowest max_turns is 4. */
    private UUID givenAgentWithMaxTurns(JsonNode outputSchema, int maxTurns) {
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "ag-" + agentId, "you analyse", "summarize_cell",
                mapper.createArrayNode(), outputSchema, maxTurns, 60, "wt-tok",
                false, null, null, null, null, null, null);
        budgetFixtures.seed(tenantId, agentId);
        return agentId;
    }

    @Test void aNudgedRunStillDeliversOnTheFinalTurnOfATightBudget() throws Exception {
        // Renamed from fallbackStillRunsWhenNudgesWouldOtherwiseExhaustMaxTurns: under parse-first
        // this scenario passes with or without the `turn < maxTurns - 1` guard, because the last
        // turn is parseable and never wants a nudge. What it does prove is that spending turn 0 on
        // a nudge still leaves the run able to finish on turn 1 of a max_turns=2 budget. The guard
        // itself is covered by theLastAvailableTurnFailsOnTheSchemaInsteadOfNudging below.
        var agentId = givenAgentWithMaxTurns(mapper.readTree("""
                {"type":"object","required":["verdicts"]}
                """), 2);

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

    @Test void theLastAvailableTurnFailsOnTheSchemaInsteadOfNudging() throws Exception {
        // The `turn < maxTurns - 1` guard: on the last available turn a nudge would burn the
        // remaining budget on "please call the tool" and the run would die with the useless
        // max_turns_exceeded instead of reporting the real schema failure. With max_turns=2 and
        // both turns unparseable, turn 1 must fail with output_schema — dropping the guard turns
        // this into max_turns_exceeded and a second nudge event.
        var agentId = givenAgentWithMaxTurns(mapper.readTree("""
                {"type":"object","required":["verdicts"]}
                """), 2);

        stub.script(
                StubLlmScripts.Turn.endTurn("kein JSON"),
                StubLlmScripts.Turn.endTurn("weiterhin kein JSON"));

        var runId = runner.startRunSync(tenantId, agentId, "manual",
                mapper.readTree("{}"), null, null, null);

        Run r = runStore.get(runId);
        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.error()).startsWith("output_schema:");
        assertThat(r.error()).doesNotContain("max_turns_exceeded");
        assertThat(countEvents(runId, "submit_result_nudged")).isEqualTo(1);
    }
}
