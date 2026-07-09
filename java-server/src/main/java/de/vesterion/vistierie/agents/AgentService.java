package de.vesterion.vistierie.agents;

import de.vesterion.vistierie.agents.dto.*;
import de.vesterion.vistierie.budget.BudgetEnforcer;
import de.vesterion.vistierie.scheduler.CronSchedules;
import de.vesterion.vistierie.streaming.StreamingSessionRepository;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
public class AgentService {

    private final AgentRepository repo;
    private final TenantRepository tenants;
    private final BudgetEnforcer budgets;
    private final AgentDefinitionValidator validator;
    private final ObjectMapper mapper;
    private final StreamingSessionRepository sessionRepo;
    private final Clock clock;

    public AgentService(AgentRepository repo, TenantRepository tenants, BudgetEnforcer budgets,
                        AgentDefinitionValidator validator, ObjectMapper mapper,
                        StreamingSessionRepository sessionRepo, Clock clock) {
        this.repo = repo;
        this.tenants = tenants;
        this.budgets = budgets;
        this.validator = validator;
        this.mapper = mapper;
        this.sessionRepo = sessionRepo;
        this.clock = clock;
    }

    public AgentDetail create(UUID tenantId, CreateAgentRequest req) {
        validator.validateName(req.name());
        validator.validateOutputSchemaIfPresent(req.output_schema());
        var existingNames = repo.findByTenant(tenantId).stream().map(Agent::name).toList();
        for (var t : req.tools()) validator.validateTool(t, existingNames);
        validator.validateSchedule(req.schedule());
        validator.validateStreaming(req.event_source_url(), req.schedule(), req.session_duration_seconds());
        var id = UUID.randomUUID();
        var toolsJson = mapper.valueToTree(req.tools());
        var mcpCredentials = req.mcp_credentials() != null ? req.mcp_credentials() : mapper.createObjectNode();
        repo.insert(id, tenantId, req.name(), req.system_prompt(), req.model_purpose(),
                toolsJson, req.output_schema(),
                req.max_turns() == null ? 25 : req.max_turns(),
                req.max_run_seconds() == null ? 1800 : req.max_run_seconds(),
                req.max_tokens(),
                req.webhook_token(), false, req.schedule(),
                req.completion_webhook(), req.completion_webhook_token(),
                req.event_source_url(), req.session_duration_seconds(),
                req.poll_interval_seconds(), mcpCredentials);
        return toDetail(repo.findById(id).orElseThrow());
    }

    public AgentDetail replace(UUID tenantId, String name, UpdateAgentRequest req) {
        var a = repo.findByName(tenantId, name).orElseThrow(() -> new NotFound(name));
        validator.validateOutputSchemaIfPresent(req.output_schema());
        var existing = repo.findByTenant(tenantId).stream()
                .map(Agent::name).filter(n -> !n.equals(name)).toList();
        for (var t : req.tools()) validator.validateTool(t, existing);
        validator.validateSchedule(req.schedule());
        validator.validateStreaming(req.event_source_url(), req.schedule(), req.session_duration_seconds());
        var toolsJson = mapper.valueToTree(req.tools());
        var mcpCredentials = req.mcp_credentials() != null ? req.mcp_credentials() : mapper.createObjectNode();
        repo.replace(a.id(), req.system_prompt(), req.model_purpose(),
                toolsJson, req.output_schema(),
                req.max_turns() == null ? 25 : req.max_turns(),
                req.max_run_seconds() == null ? 1800 : req.max_run_seconds(),
                req.max_tokens(),
                req.webhook_token(), a.paused(), req.schedule(),
                req.completion_webhook(), req.completion_webhook_token(),
                req.event_source_url(), req.session_duration_seconds(),
                req.poll_interval_seconds(), mcpCredentials);
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
        Integer newMaxTokens = req.max_tokens() != null ? req.max_tokens() : a.maxTokens();
        var newToken = req.webhook_token() != null ? req.webhook_token() : a.webhookToken();
        boolean newPaused = req.paused() != null ? req.paused() : a.paused();
        String newSchedule;
        if (req.schedule() != null) {
            newSchedule = req.schedule().isBlank() ? null : req.schedule();
            validator.validateSchedule(newSchedule);
        } else {
            newSchedule = a.schedule();
        }
        String newEventSourceUrl;
        if (req.event_source_url() != null) {
            newEventSourceUrl = req.event_source_url().isBlank() ? null : req.event_source_url();
        } else {
            newEventSourceUrl = a.eventSourceUrl();
        }
        Integer newSessionDuration = req.session_duration_seconds() != null
                ? req.session_duration_seconds() : a.sessionDurationSeconds();
        Integer newPollInterval = req.poll_interval_seconds() != null
                ? req.poll_interval_seconds() : a.pollIntervalSeconds();

        if (req.tools() != null) {
            var existing = repo.findByTenant(tenantId).stream()
                    .map(Agent::name).filter(n -> !n.equals(name)).toList();
            for (var t : req.tools()) validator.validateTool(t, existing);
        }
        if (req.output_schema() != null) {
            validator.validateOutputSchemaIfPresent(req.output_schema());
        }
        validator.validateStreaming(newEventSourceUrl, newSchedule, newSessionDuration);
        if (a.paused() && !newPaused) {
            var tenant = tenants.findById(tenantId).orElseThrow();
            budgets.checkOrThrow(tenantId, tenant.name(), a.id(), a.name());
        }
        String newCompletionWebhook = req.completion_webhook() != null
                ? (req.completion_webhook().isBlank() ? null : req.completion_webhook())
                : a.completionWebhook();
        String newCompletionWebhookToken = req.completion_webhook_token() != null
                ? (req.completion_webhook_token().isBlank() ? null : req.completion_webhook_token())
                : a.completionWebhookToken();
        repo.replace(a.id(), newSysPrompt, newPurpose, newTools, newOutSchema,
                newMaxTurns, newMaxSeconds, newMaxTokens, newToken, newPaused, newSchedule,
                newCompletionWebhook, newCompletionWebhookToken,
                newEventSourceUrl, newSessionDuration, newPollInterval, a.mcpCredentials());
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
                    a.maxTurns(), a.maxRunSeconds(), a.maxTokens(), a.paused(), a.version(),
                    a.createdAt(), a.updatedAt(),
                    a.schedule(), a.lastTickAt(),
                    a.completionWebhook(), a.completionWebhookToken(),
                    a.eventSourceUrl(), a.sessionDurationSeconds(), a.pollIntervalSeconds(),
                    CronSchedules.nextRunAt(a.schedule(), clock.instant()));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public List<StreamingSessionDto> listSessions(UUID tenantId, String name) {
        var a = repo.findByName(tenantId, name).orElseThrow(() -> new NotFound(name));
        return sessionRepo.listByAgent(a.id(), 50).stream()
                .map(s -> new StreamingSessionDto(s.id(), s.openedAt(), s.closesAt(),
                        s.lastPollAt(), s.status()))
                .toList();
    }

    public static class NotFound extends RuntimeException { public NotFound(String n) { super("agent not found: " + n); } }
    public static class ReferencedException extends RuntimeException { public ReferencedException(String n) { super("agent " + n + " is referenced"); } }
}
