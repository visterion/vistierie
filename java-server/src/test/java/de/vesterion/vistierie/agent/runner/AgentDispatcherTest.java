package de.vesterion.vistierie.agent.runner;

import de.vesterion.vistierie.agents.Agent;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.runs.RunStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentDispatcherTest {

    private final AgentRepository agents = mock(AgentRepository.class);
    private final RunStore runs = mock(RunStore.class);
    private final AsyncRunExecutor asyncExecutor = mock(AsyncRunExecutor.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentDispatcher dispatcher = new AgentDispatcher(agents, runs, asyncExecutor, mapper);

    private Agent agent(UUID id, UUID tenantId, String name) {
        return new Agent(id, tenantId, name, "sys", "purpose",
                mapper.createArrayNode(), null,
                25, 1800, "tok", false, 3,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"),
                null, null, null, null, null, null, null);
    }

    @Test void triggerCreatesRunAndKicksOffRunner() {
        var tenantId = UUID.randomUUID();
        var agentId = UUID.randomUUID();
        var agent = agent(agentId, tenantId, "summ");
        var payload = mapper.createObjectNode().put("k", "v");

        var runId = dispatcher.trigger(tenantId, agent, "manual", payload,
                "https://done", "wt");

        assertThat(runId)
                .isNotBlank()
                .doesNotContain("-")
                .matches("[A-F0-9]{32}");

        var snapCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(runs).create(eq(runId), eq(tenantId), eq(agentId),
                snapCaptor.capture(), eq(3),
                eq(null), eq("manual"),
                eq(payload), eq("https://done"), eq("wt"), isNull());
        verify(asyncExecutor).execute(runId);

        var snap = snapCaptor.getValue();
        assertThat(snap.get("name").asString()).isEqualTo("summ");
        assertThat(snap.get("system_prompt").asString()).isEqualTo("sys");
        assertThat(snap.get("model_purpose").asString()).isEqualTo("purpose");
        assertThat(snap.get("max_turns").asInt()).isEqualTo(25);
        assertThat(snap.get("max_run_seconds").asInt()).isEqualTo(1800);
        assertThat(snap.get("webhook_token").asString()).isEqualTo("tok");
        assertThat(snap.has("tools")).isTrue();
    }
}
