package de.vesterion.vistierie.agents;

import de.vesterion.vistierie.agents.dto.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

@Service
public class AgentService {

    private final AgentRepository repo;
    private final AgentDefinitionValidator validator;
    private final ObjectMapper mapper;

    public AgentService(AgentRepository repo, AgentDefinitionValidator validator, ObjectMapper mapper) {
        this.repo = repo; this.validator = validator; this.mapper = mapper;
    }

    public AgentDetail create(UUID tenantId, CreateAgentRequest req) {
        validator.validateName(req.name());
        validator.validateOutputSchemaIfPresent(req.output_schema());
        var existingNames = repo.findByTenant(tenantId).stream().map(Agent::name).toList();
        for (var t : req.tools()) validator.validateTool(t, existingNames);

        validator.validateSchedule(req.schedule());
        var id = UUID.randomUUID();
        var toolsJson = mapper.valueToTree(req.tools());
        repo.insert(id, tenantId, req.name(), req.system_prompt(), req.model_purpose(),
                toolsJson, req.output_schema(),
                req.max_turns() == null ? 25 : req.max_turns(),
                req.max_run_seconds() == null ? 1800 : req.max_run_seconds(),
                req.webhook_token(), false, req.schedule());
        return toDetail(repo.findById(id).orElseThrow());
    }

    public AgentDetail replace(UUID tenantId, String name, UpdateAgentRequest req) {
        var a = repo.findByName(tenantId, name).orElseThrow(() -> new NotFound(name));
        validator.validateOutputSchemaIfPresent(req.output_schema());
        var existing = repo.findByTenant(tenantId).stream()
                .map(Agent::name).filter(n -> !n.equals(name)).toList();
        for (var t : req.tools()) validator.validateTool(t, existing);
        validator.validateSchedule(req.schedule());
        var toolsJson = mapper.valueToTree(req.tools());
        repo.replace(a.id(), req.system_prompt(), req.model_purpose(),
                toolsJson, req.output_schema(),
                req.max_turns() == null ? 25 : req.max_turns(),
                req.max_run_seconds() == null ? 1800 : req.max_run_seconds(),
                req.webhook_token(), a.paused(), req.schedule());
        return toDetail(repo.findById(a.id()).orElseThrow());
    }

    public AgentDetail patch(UUID tenantId, String name, PatchAgentRequest req) {
        var a = repo.findByName(tenantId, name).orElseThrow(() -> new NotFound(name));
        var newSysPrompt = req.system_prompt() != null ? req.system_prompt() : a.systemPrompt();
        var newPurpose = req.model_purpose() != null ? req.model_purpose() : a.modelPurpose();
        JsonNode newTools = req.tools() != null ? mapper.valueToTree(req.tools()) : a.tools();
        var newOutSchema = req.output_schema() != null ? req.output_schema() : a.outputSchema();
        int newMaxTurns = req.max_turns() != null ? req.max_turns() : a.maxTurns();
        int newMaxSeconds = req.max_run_seconds() != null ? req.max_run_seconds() : a.maxRunSeconds();
        var newToken = req.webhook_token() != null ? req.webhook_token() : a.webhookToken();
        boolean newPaused = req.paused() != null ? req.paused() : a.paused();
        String newSchedule;
        if (req.schedule() != null) {
            // Treat empty string as "clear schedule"
            newSchedule = req.schedule().isBlank() ? null : req.schedule();
            validator.validateSchedule(newSchedule);
        } else {
            newSchedule = a.schedule();
        }

        if (req.tools() != null) {
            var existing = repo.findByTenant(tenantId).stream()
                    .map(Agent::name).filter(n -> !n.equals(name)).toList();
            for (var t : req.tools()) validator.validateTool(t, existing);
        }
        if (req.output_schema() != null) {
            validator.validateOutputSchemaIfPresent(req.output_schema());
        }
        repo.replace(a.id(), newSysPrompt, newPurpose, newTools, newOutSchema,
                newMaxTurns, newMaxSeconds, newToken, newPaused, newSchedule);
        return toDetail(repo.findById(a.id()).orElseThrow());
    }

    public void delete(UUID tenantId, String name) {
        var a = repo.findByName(tenantId, name).orElseThrow(() -> new NotFound(name));
        if (repo.anyReferencesSubagent(tenantId, name, a.id())) {
            throw new ReferencedException(name);
        }
        repo.delete(a.id());
    }

    public List<AgentSummary> list(UUID tenantId) {
        return repo.findByTenant(tenantId).stream()
                .map(a -> new AgentSummary(a.id().toString(), a.name(), a.version(), a.paused(), a.updatedAt()))
                .toList();
    }

    public AgentDetail get(UUID tenantId, String name) {
        return toDetail(repo.findByName(tenantId, name).orElseThrow(() -> new NotFound(name)));
    }

    private AgentDetail toDetail(Agent a) {
        try {
            List<ToolDef> tools = mapper.treeToValue(a.tools(), mapper.getTypeFactory()
                    .constructCollectionType(List.class, ToolDef.class));
            return new AgentDetail(a.id().toString(), a.name(),
                    a.systemPrompt(), a.modelPurpose(), tools, a.outputSchema(),
                    a.maxTurns(), a.maxRunSeconds(), a.paused(), a.version(),
                    a.createdAt(), a.updatedAt(),
                    a.schedule(), a.lastTickAt());
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static class NotFound extends RuntimeException { public NotFound(String n) { super("agent not found: " + n); } }
    public static class ReferencedException extends RuntimeException { public ReferencedException(String n) { super("agent " + n + " is referenced"); } }
}
