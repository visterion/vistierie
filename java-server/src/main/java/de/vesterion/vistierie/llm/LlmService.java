package de.vesterion.vistierie.llm;

import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.audit.LlmCallRecorder;
import de.vesterion.vistierie.budget.BudgetEnforcer;
import de.vesterion.vistierie.budget.BudgetException;
import de.vesterion.vistierie.auth.RequestContext;
import de.vesterion.vistierie.kill.KillSwitchService;
import de.vesterion.vistierie.llm.dto.CompleteRequest;
import de.vesterion.vistierie.llm.dto.LlmResponse;
import de.vesterion.vistierie.llm.dto.MultiVisionRequest;
import de.vesterion.vistierie.llm.dto.VisionRequest;
import de.vesterion.vistierie.pricing.PriceTable;
import de.vesterion.vistierie.provider.ClaudeSubscriptionProvider;
import de.vesterion.vistierie.provider.LlmProvider;
import de.vesterion.vistierie.provider.ProviderRegistry;
import de.vesterion.vistierie.provider.ProviderRequest;
import de.vesterion.vistierie.provider.ProviderResponse;
import de.vesterion.vistierie.routing.RoutingDecision;
import de.vesterion.vistierie.routing.RoutingResolver;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class LlmService {

    private final RoutingResolver routing;
    private final ProviderRegistry providers;
    private final PriceTable prices;
    private final LlmCallRecorder recorder;
    private final KillSwitchService kill;
    private final LlmMetrics metrics;
    private final AgentRepository agents;
    private final BudgetEnforcer budgets;

    public LlmService(RoutingResolver routing, ProviderRegistry providers,
                      PriceTable prices, LlmCallRecorder recorder, KillSwitchService kill,
                      LlmMetrics metrics, AgentRepository agents, BudgetEnforcer budgets) {
        this.routing = routing;
        this.providers = providers;
        this.prices = prices;
        this.recorder = recorder;
        this.kill = kill;
        this.metrics = metrics;
        this.agents = agents;
        this.budgets = budgets;
    }

    public record InvocationResult(LlmResponse response, BudgetEnforcer.BudgetCheckResult budget) {}

    /** Endpoint-specific provider invocation; model varies between primary and fallback attempt. */
    private interface ProviderCall {
        ProviderResponse run(LlmProvider provider, String model);
    }

    private record CallContext(UUID tenantId, UUID agentId, String purpose, String realm,
                               String endpoint, BudgetEnforcer.BudgetCheckResult budget) {}

    public InvocationResult complete(CompleteRequest req) {
        var tenantId = RequestContext.requireTenantId();
        var tenant = RequestContext.requireTenantName();
        var agent = agents.findByName(tenantId, req.agent_name())
                .orElseThrow(() -> BudgetException.agentNotFound(tenant, req.agent_name()));
        var budget = budgets.checkOrThrow(tenantId, tenant, agent.id(), agent.name());

        var killBlocked = isKilled(tenantId);
        if (killBlocked != null) {
            recordKilled(randomCallId(), tenantId, req.purpose(), req.realm(), "complete");
            metrics.record("n/a", "n/a", "complete", "killed", 0, 0);
            throw killBlocked;
        }

        var decision = routing.resolve(tenant, req.realm(), req.purpose(), req.model());
        int maxTokens = req.max_tokens() == null ? 1024 : req.max_tokens();
        var ctx = new CallContext(tenantId, agent.id(), req.purpose(), req.realm(), "complete", budget);

        java.util.function.Function<String, ProviderRequest> reqForModel = model ->
                new ProviderRequest(model, maxTokens, req.temperature(), req.system(),
                        req.messages(), null, null, null);

        return invoke(ctx, decision, reqForModel,
                (provider, model) -> provider.complete(reqForModel.apply(model)));
    }

    public InvocationResult vision(VisionRequest req) {
        var tenantId = RequestContext.requireTenantId();
        var tenant = RequestContext.requireTenantName();
        var agent = agents.findByName(tenantId, req.agent_name())
                .orElseThrow(() -> BudgetException.agentNotFound(tenant, req.agent_name()));
        var budget = budgets.checkOrThrow(tenantId, tenant, agent.id(), agent.name());

        var killBlocked = isKilled(tenantId);
        if (killBlocked != null) {
            recordKilled(randomCallId(), tenantId, req.purpose(), req.realm(), "vision");
            metrics.record("n/a", "n/a", "vision", "killed", 0, 0);
            throw killBlocked;
        }

        var decision = routing.resolve(tenant, req.realm(), req.purpose(), req.model());
        int maxTokens = req.max_tokens() == null ? 1024 : req.max_tokens();
        var ctx = new CallContext(tenantId, agent.id(), req.purpose(), req.realm(), "vision", budget);

        java.util.function.Function<String, ProviderRequest> reqForModel = model ->
                new ProviderRequest(
                        model, maxTokens, null, null,
                        java.util.List.of(java.util.Map.of("role", "user", "content",
                            java.util.List.of(
                                java.util.Map.of("type", "image",
                                       "source", java.util.Map.of("type", "base64",
                                                        "media_type", req.image().media_type(),
                                                        "data", req.image().data())),
                                java.util.Map.of("type", "text", "text", req.prompt())))),
                        null, null, null);

        return invoke(ctx, decision, reqForModel,
                (provider, model) -> provider.vision(model, maxTokens,
                        req.image().media_type(), req.image().data(), req.prompt()));
    }

    public InvocationResult visionMulti(MultiVisionRequest req) {
        var tenantId = RequestContext.requireTenantId();
        var tenant = RequestContext.requireTenantName();
        var agent = agents.findByName(tenantId, req.agent_name())
                .orElseThrow(() -> BudgetException.agentNotFound(tenant, req.agent_name()));
        var budget = budgets.checkOrThrow(tenantId, tenant, agent.id(), agent.name());

        var killBlocked = isKilled(tenantId);
        if (killBlocked != null) {
            recordKilled(randomCallId(), tenantId, req.purpose(), req.realm(), "vision-multi");
            metrics.record("n/a", "n/a", "vision-multi", "killed", 0, 0);
            throw killBlocked;
        }

        var decision = routing.resolve(tenant, req.realm(), req.purpose(), req.model());
        int maxTokens = req.max_tokens() == null ? 1024 : req.max_tokens();
        var ctx = new CallContext(tenantId, agent.id(), req.purpose(), req.realm(), "vision-multi", budget);

        var content = new java.util.ArrayList<Object>();
        var images = new java.util.ArrayList<LlmProvider.ImageInput>();
        for (var img : req.images()) {
            content.add(java.util.Map.of("type", "image",
                    "source", java.util.Map.of("type", "base64",
                            "media_type", img.media_type(), "data", img.data())));
            images.add(new LlmProvider.ImageInput(img.media_type(), img.data()));
        }
        content.add(java.util.Map.of("type", "text", "text", req.prompt()));

        java.util.function.Function<String, ProviderRequest> reqForModel = model ->
                new ProviderRequest(model, maxTokens, null, null,
                        java.util.List.of(java.util.Map.of("role", "user", "content", content)),
                        null, null, null);

        return invoke(ctx, decision, reqForModel,
                (provider, model) -> provider.visionMulti(model, maxTokens, images, req.prompt()));
    }

    private InvocationResult invoke(CallContext ctx, RoutingDecision decision,
                                    java.util.function.Function<String, ProviderRequest> reqForModel,
                                    ProviderCall call) {
        try {
            return attempt(ctx, decision.provider(), decision.model(), reqForModel, call);
        } catch (RuntimeException e) {
            if (decision.fallbackProvider() == null || !shouldFallback(e)) throw e;
            metrics.recordFallback(decision.provider(), decision.fallbackProvider(), fallbackReason(e));
            return attempt(ctx, decision.fallbackProvider(), decision.fallbackModel(), reqForModel, call);
        }
    }

    /** One provider attempt with its own call id, audit row, and metrics. */
    private InvocationResult attempt(CallContext ctx, String providerName, String model,
                                     java.util.function.Function<String, ProviderRequest> reqForModel,
                                     ProviderCall call) {
        var provider = providers.get(providerName);
        var pReq = reqForModel.apply(model);
        var id = randomCallId();
        var start = System.nanoTime();
        try {
            var pRes = call.run(provider, model);
            var dur = (int) ((System.nanoTime() - start) / 1_000_000);
            boolean subscription = ClaudeSubscriptionProvider.NAME.equals(providerName);
            long cost = subscription ? 0L : prices.costMicros(model, pRes.usage());
            Long shadow = subscription ? shadowCost(model, pRes.usage()) : null;
            recorder.insertWithBody(new LlmCallRecorder.Row(
                    id, ctx.tenantId(), ctx.agentId(), ctx.purpose(), ctx.realm(),
                    providerName, model, ctx.endpoint(),
                    pRes.usage().inputTokens(), pRes.usage().outputTokens(),
                    pRes.usage().cacheCreationInputTokens(), pRes.usage().cacheReadInputTokens(),
                    cost, shadow, dur, "ok", null, null, null), pReq, pRes);
            metrics.record(providerName, model, ctx.endpoint(), "ok", dur, cost);
            if (shadow != null) {
                metrics.recordShadowCost(providerName, model, ctx.endpoint(), shadow);
            }
            return new InvocationResult(new LlmResponse(pRes.text(), pRes.stopReason(),
                    pRes.usage(), providerName, model, cost, id), ctx.budget());
        } catch (LlmProvider.ProviderException e) {
            recordFailure(ctx, id, providerName, model, pReq, start,
                    e.statusCode() >= 500 ? "error" : "rate_limited", e.errorCode());
            throw e;
        } catch (UnsupportedOperationException e) {
            recordFailure(ctx, id, providerName, model, pReq, start, "error", "unsupported_operation");
            throw e;
        }
    }

    private void recordFailure(CallContext ctx, String id, String providerName, String model,
                               ProviderRequest pReq, long start, String status, String errorCode) {
        var dur = (int) ((System.nanoTime() - start) / 1_000_000);
        recorder.insertWithBody(new LlmCallRecorder.Row(
                id, ctx.tenantId(), ctx.agentId(), ctx.purpose(), ctx.realm(),
                providerName, model, ctx.endpoint(),
                0, 0, 0, 0, 0, null, dur, status, errorCode, null, null), pReq, null);
        metrics.record(providerName, model, ctx.endpoint(), status, dur, 0);
    }

    private static boolean shouldFallback(RuntimeException e) {
        if (e instanceof UnsupportedOperationException) return true;
        if (e instanceof LlmProvider.ProviderException pe) {
            return pe.statusCode() == 429 || pe.statusCode() >= 500;
        }
        return false;
    }

    private static String fallbackReason(RuntimeException e) {
        if (e instanceof UnsupportedOperationException) return "unsupported";
        return ((LlmProvider.ProviderException) e).statusCode() == 429 ? "rate_limited" : "error";
    }

    private Long shadowCost(String model, de.vesterion.vistierie.pricing.Usage usage) {
        try {
            return prices.costMicros(model, usage);
        } catch (PriceTable.UnknownModelException e) {
            return null;
        }
    }

    private KillSwitchService.KilledException isKilled(UUID tenantId) {
        try {
            kill.check(tenantId);
            return null;
        } catch (KillSwitchService.KilledException e) {
            return e;
        }
    }

    private void recordKilled(String id, UUID tenantId, String purpose, String realm, String endpoint) {
        recorder.insert(new LlmCallRecorder.Row(
                id, tenantId, null, purpose, realm,
                "n/a", "n/a", endpoint,
                0, 0, 0, 0, 0, 0, "killed", null, null, null));
    }

    private static String randomCallId() {
        // Compact opaque id; not a real ULID, just a hex-style identifier.
        return UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }
}
