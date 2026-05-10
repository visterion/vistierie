package de.vesterion.vistierie.agents;

import de.vesterion.vistierie.agents.dto.CreateAgentRequest;
import de.vesterion.vistierie.agents.dto.PatchAgentRequest;
import de.vesterion.vistierie.agents.dto.ToolDef;
import de.vesterion.vistierie.agents.dto.UpdateAgentRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentServiceTest {

    private final AgentRepository repo = mock(AgentRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentDefinitionValidator validator = new AgentDefinitionValidator(new JsonSchemas());
    private final AgentService svc = new AgentService(repo, validator, mapper);

    private final UUID tenantId = UUID.randomUUID();

    private JsonNode schema(String json) {
        try { return mapper.readTree(json); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private ToolDef httpTool() {
        return new ToolDef("fetch", "desc", schema("{\"type\":\"object\"}"),
                null, null, "https://example.com/hook", 30);
    }

    private Agent existing(UUID id, String name) {
        return new Agent(id, tenantId, name, "sys", "purpose",
                mapper.createArrayNode(), null,
                25, 1800, "tok", false, 1,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"),
                null, null);
    }

    @Test void createAppliesDefaultsAndPersists() {
        var req = new CreateAgentRequest("agent-a", "sys", "purpose",
                List.of(httpTool()), null, null, null, "tok", null);
        when(repo.findByTenant(tenantId)).thenReturn(List.of());
        UUID[] inserted = new UUID[1];
        org.mockito.Mockito.doAnswer(inv -> {
            inserted[0] = inv.getArgument(0);
            return null;
        }).when(repo).insert(any(), eq(tenantId), eq("agent-a"),
                eq("sys"), eq("purpose"), any(), any(),
                eq(25), eq(1800), eq("tok"), eq(false), eq(null));
        when(repo.findById(any())).thenAnswer(inv -> Optional.of(existing(inv.getArgument(0), "agent-a")));

        var detail = svc.create(tenantId, req);
        assertThat(detail.name()).isEqualTo("agent-a");
        assertThat(inserted[0]).isNotNull();
    }

    @Test void createRejectsBadName() {
        var req = new CreateAgentRequest("Bad Name!", "sys", "purpose",
                List.of(), null, null, null, "tok", null);
        assertThatThrownBy(() -> svc.create(tenantId, req))
                .isInstanceOf(AgentDefinitionValidator.InvalidDefinitionException.class);
        verify(repo, never()).insert(any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt(), any(), anyBoolean(), any());
    }

    @Test void createRejectsBadSchedule() {
        var req = new CreateAgentRequest("agent-b", "sys", "purpose",
                List.of(), null, null, null, "tok", "not-a-cron");
        when(repo.findByTenant(tenantId)).thenReturn(List.of());
        assertThatThrownBy(() -> svc.create(tenantId, req))
                .isInstanceOf(AgentDefinitionValidator.InvalidDefinitionException.class);
    }

    @Test void replaceUpdatesExistingPreservingPaused() {
        var id = UUID.randomUUID();
        var existing = new Agent(id, tenantId, "agent-c", "old", "old-p",
                mapper.createArrayNode(), null, 10, 60, "old-tok", true, 1,
                Instant.now(), Instant.now(), null, null);
        when(repo.findByName(tenantId, "agent-c")).thenReturn(Optional.of(existing));
        when(repo.findByTenant(tenantId)).thenReturn(List.of(existing));
        when(repo.findById(id)).thenReturn(Optional.of(existing));

        var req = new UpdateAgentRequest("new-sys", "new-p", List.of(httpTool()),
                null, 50, 600, "new-tok", null);
        svc.replace(tenantId, "agent-c", req);

        verify(repo).replace(eq(id), eq("new-sys"), eq("new-p"), any(), eq(null),
                eq(50), eq(600), eq("new-tok"), eq(true), eq(null));
    }

    @Test void replaceThrowsNotFound() {
        when(repo.findByName(tenantId, "missing")).thenReturn(Optional.empty());
        var req = new UpdateAgentRequest("sys", "p", List.of(), null, null, null, "tok", null);
        assertThatThrownBy(() -> svc.replace(tenantId, "missing", req))
                .isInstanceOf(AgentService.NotFound.class);
    }

    @Test void patchMergesNullsWithExistingValues() {
        var id = UUID.randomUUID();
        var existing = new Agent(id, tenantId, "agent-d", "keep-sys", "keep-p",
                mapper.createArrayNode(), null, 7, 70, "keep-tok", false, 1,
                Instant.now(), Instant.now(), "0 0 0 * * *", null);
        when(repo.findByName(tenantId, "agent-d")).thenReturn(Optional.of(existing));
        when(repo.findById(id)).thenReturn(Optional.of(existing));

        var patch = new PatchAgentRequest(true, null, null, null, null, null, null, null, null);
        svc.patch(tenantId, "agent-d", patch);

        verify(repo).replace(eq(id), eq("keep-sys"), eq("keep-p"), any(), eq(null),
                eq(7), eq(70), eq("keep-tok"), eq(true), eq("0 0 0 * * *"));
    }

    @Test void patchEmptyScheduleClearsIt() {
        var id = UUID.randomUUID();
        var existing = new Agent(id, tenantId, "agent-e", "sys", "p",
                mapper.createArrayNode(), null, 25, 1800, "tok", false, 1,
                Instant.now(), Instant.now(), "0 0 0 * * *", null);
        when(repo.findByName(tenantId, "agent-e")).thenReturn(Optional.of(existing));
        when(repo.findById(id)).thenReturn(Optional.of(existing));

        var patch = new PatchAgentRequest(null, null, null, null, null, null, null, null, "");
        svc.patch(tenantId, "agent-e", patch);

        verify(repo).replace(eq(id), any(), any(), any(), any(),
                anyInt(), anyInt(), any(), anyBoolean(), eq(null));
    }

    @Test void patchValidatesNewToolsAgainstOtherAgents() {
        var id = UUID.randomUUID();
        var existing = new Agent(id, tenantId, "agent-f", "sys", "p",
                mapper.createArrayNode(), null, 25, 1800, "tok", false, 1,
                Instant.now(), Instant.now(), null, null);
        when(repo.findByName(tenantId, "agent-f")).thenReturn(Optional.of(existing));
        when(repo.findByTenant(tenantId)).thenReturn(List.of(existing));

        var subagent = new ToolDef("dispatch", "desc", schema("{\"type\":\"object\"}"),
                "subagent", "ghost", null, null);
        var patch = new PatchAgentRequest(null, null, null, List.of(subagent),
                null, null, null, null, null);

        assertThatThrownBy(() -> svc.patch(tenantId, "agent-f", patch))
                .isInstanceOf(AgentDefinitionValidator.InvalidDefinitionException.class)
                .hasMessageContaining("target_agent");
    }

    @Test void deleteRefusesIfReferenced() {
        var id = UUID.randomUUID();
        var existing = existing(id, "sub");
        when(repo.findByName(tenantId, "sub")).thenReturn(Optional.of(existing));
        when(repo.anyReferencesSubagent(tenantId, "sub", id)).thenReturn(true);

        assertThatThrownBy(() -> svc.delete(tenantId, "sub"))
                .isInstanceOf(AgentService.ReferencedException.class);
        verify(repo, never()).delete(any());
    }

    @Test void deleteRemovesWhenUnreferenced() {
        var id = UUID.randomUUID();
        var existing = existing(id, "loner");
        when(repo.findByName(tenantId, "loner")).thenReturn(Optional.of(existing));
        when(repo.anyReferencesSubagent(tenantId, "loner", id)).thenReturn(false);

        svc.delete(tenantId, "loner");
        verify(repo).delete(id);
    }

    @Test void getThrowsNotFound() {
        when(repo.findByName(tenantId, "nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.get(tenantId, "nope"))
                .isInstanceOf(AgentService.NotFound.class);
    }

    @Test void listMapsToSummaries() {
        var a1 = existing(UUID.randomUUID(), "a1");
        var a2 = existing(UUID.randomUUID(), "a2");
        when(repo.findByTenant(tenantId)).thenReturn(List.of(a1, a2));

        var summaries = svc.list(tenantId);
        assertThat(summaries).hasSize(2);
        assertThat(summaries.get(0).name()).isEqualTo("a1");
        assertThat(summaries.get(1).name()).isEqualTo("a2");
    }

    @Test void createRejectsDuplicateSubagentNotFound() {
        // subagent target must exist among other agents
        var sub = new ToolDef("dispatch", "desc", schema("{\"type\":\"object\"}"),
                "subagent", "ghost", null, null);
        var req = new CreateAgentRequest("agent-g", "sys", "purpose",
                List.of(sub), null, null, null, "tok", null);
        when(repo.findByTenant(tenantId)).thenReturn(List.of());
        assertThatThrownBy(() -> svc.create(tenantId, req))
                .isInstanceOf(AgentDefinitionValidator.InvalidDefinitionException.class);
    }

    @Test void detailDeserializesToolsRoundtrip() {
        // create an agent with one HTTP tool, then ensure detail returns it deserialized
        var req = new CreateAgentRequest("agent-h", "sys", "purpose",
                List.of(httpTool()), null, null, null, "tok", null);
        when(repo.findByTenant(tenantId)).thenReturn(List.of());

        var captor = ArgumentCaptor.forClass(JsonNode.class);
        org.mockito.Mockito.doAnswer(inv -> null)
                .when(repo).insert(any(), eq(tenantId), eq("agent-h"), any(), any(),
                        captor.capture(), any(), anyInt(), anyInt(), any(), anyBoolean(), any());

        when(repo.findById(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return Optional.of(new Agent(id, tenantId, "agent-h", "sys", "purpose",
                    captor.getValue(), null, 25, 1800, "tok", false, 1,
                    Instant.now(), Instant.now(), null, null));
        });

        var detail = svc.create(tenantId, req);
        assertThat(detail.tools()).hasSize(1);
        assertThat(detail.tools().get(0).name()).isEqualTo("fetch");
        assertThat(detail.tools().get(0).webhook_url()).isEqualTo("https://example.com/hook");
    }
}
