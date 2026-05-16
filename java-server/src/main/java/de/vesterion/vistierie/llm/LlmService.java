package de.vesterion.vistierie.llm;

import de.vesterion.vistierie.audit.LlmCallRecorder;
import de.vesterion.vistierie.auth.RequestContext;
import de.vesterion.vistierie.kill.KillSwitchService;
import de.vesterion.vistierie.llm.dto.CompleteRequest;
import de.vesterion.vistierie.llm.dto.LlmResponse;
import de.vesterion.vistierie.llm.dto.VisionRequest;
import de.vesterion.vistierie.pricing.PriceTable;
import de.vesterion.vistierie.provider.LlmProvider;
import de.vesterion.vistierie.provider.ProviderRegistry;
import de.vesterion.vistierie.provider.ProviderRequest;
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

    public LlmService(RoutingResolver routing, ProviderRegistry providers,
                      PriceTable prices, LlmCallRecorder recorder, KillSwitchService kill,
                      LlmMetrics metrics) {
        this.routing = routing;
        this.providers = providers;
        this.prices = prices;
        this.recorder = recorder;
        this.kill = kill;
        this.metrics = metrics;
    }

    public LlmResponse complete(CompleteRequest req) {
        var tenantId = RequestContext.requireTenantId();
        var tenant = RequestContext.requireTenantName();
        var id = randomCallId();

        var killBlocked = isKilled(tenantId);
        if (killBlocked != null) {
            recordKilled(id, tenantId, req.purpose(), req.realm(), "complete");
            metrics.record("n/a", "n/a", "complete", "killed", 0, 0);
            throw killBlocked;
        }

        var decision = routing.resolve(tenant, req.realm(), req.purpose(), req.model());
        var provider = providers.get(decision.provider());

        var pReq = new ProviderRequest(
                decision.model(),
                req.max_tokens() == null ? 1024 : req.max_tokens(),
                req.temperature(),
                req.system(),
                req.messages(),
                null, null, null);

        var start = System.nanoTime();
        try {
            var pRes = provider.complete(pReq);
            var dur = (int) ((System.nanoTime() - start) / 1_000_000);
            var cost = prices.costMicros(decision.model(), pRes.usage());
            recorder.insertWithBody(new LlmCallRecorder.Row(
                    id, tenantId, null, req.purpose(), req.realm(),
                    decision.provider(), decision.model(), "complete",
                    pRes.usage().inputTokens(), pRes.usage().outputTokens(),
                    pRes.usage().cacheCreationInputTokens(), pRes.usage().cacheReadInputTokens(),
                    cost, dur, "ok", null, null, null), pReq, pRes);
            metrics.record(decision.provider(), decision.model(), "complete", "ok", dur, cost);
            return new LlmResponse(pRes.text(), pRes.stopReason(), pRes.usage(),
                    decision.provider(), decision.model(), cost, id);
        } catch (LlmProvider.ProviderException e) {
            var dur = (int) ((System.nanoTime() - start) / 1_000_000);
            var status = e.statusCode() >= 500 ? "error" : "rate_limited";
            recorder.insertWithBody(new LlmCallRecorder.Row(
                    id, tenantId, null, req.purpose(), req.realm(),
                    decision.provider(), decision.model(), "complete",
                    0, 0, 0, 0, 0, dur, status,
                    e.errorCode(), null, null), pReq, null);
            metrics.record(decision.provider(), decision.model(), "complete", status, dur, 0);
            throw e;
        }
    }

    public LlmResponse vision(VisionRequest req) {
        var tenantId = RequestContext.requireTenantId();
        var tenant = RequestContext.requireTenantName();
        var id = randomCallId();

        var killBlocked = isKilled(tenantId);
        if (killBlocked != null) {
            recordKilled(id, tenantId, req.purpose(), req.realm(), "vision");
            metrics.record("n/a", "n/a", "vision", "killed", 0, 0);
            throw killBlocked;
        }

        var decision = routing.resolve(tenant, req.realm(), req.purpose(), req.model());
        var provider = providers.get(decision.provider());

        var pReq = new ProviderRequest(
                decision.model(),
                req.max_tokens() == null ? 1024 : req.max_tokens(),
                null, null,
                java.util.List.of(java.util.Map.of("role", "user", "content",
                    java.util.List.of(
                        java.util.Map.of("type", "image",
                               "source", java.util.Map.of("type", "base64",
                                                "media_type", req.image().media_type(),
                                                "data", req.image().data())),
                        java.util.Map.of("type", "text", "text", req.prompt())))),
                null, null, null);

        var start = System.nanoTime();
        try {
            var pRes = provider.vision(decision.model(),
                    req.max_tokens() == null ? 1024 : req.max_tokens(),
                    req.image().media_type(), req.image().data(), req.prompt());
            var dur = (int) ((System.nanoTime() - start) / 1_000_000);
            var cost = prices.costMicros(decision.model(), pRes.usage());
            recorder.insertWithBody(new LlmCallRecorder.Row(
                    id, tenantId, null, req.purpose(), req.realm(),
                    decision.provider(), decision.model(), "vision",
                    pRes.usage().inputTokens(), pRes.usage().outputTokens(),
                    pRes.usage().cacheCreationInputTokens(), pRes.usage().cacheReadInputTokens(),
                    cost, dur, "ok", null, null, null), pReq, pRes);
            metrics.record(decision.provider(), decision.model(), "vision", "ok", dur, cost);
            return new LlmResponse(pRes.text(), pRes.stopReason(), pRes.usage(),
                    decision.provider(), decision.model(), cost, id);
        } catch (LlmProvider.ProviderException e) {
            var dur = (int) ((System.nanoTime() - start) / 1_000_000);
            var status = e.statusCode() >= 500 ? "error" : "rate_limited";
            recorder.insertWithBody(new LlmCallRecorder.Row(
                    id, tenantId, null, req.purpose(), req.realm(),
                    decision.provider(), decision.model(), "vision",
                    0, 0, 0, 0, 0, dur, status,
                    e.errorCode(), null, null), pReq, null);
            metrics.record(decision.provider(), decision.model(), "vision", status, dur, 0);
            throw e;
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
