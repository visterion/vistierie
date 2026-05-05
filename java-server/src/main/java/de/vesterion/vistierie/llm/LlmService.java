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

    public LlmService(RoutingResolver routing, ProviderRegistry providers,
                      PriceTable prices, LlmCallRecorder recorder, KillSwitchService kill) {
        this.routing = routing;
        this.providers = providers;
        this.prices = prices;
        this.recorder = recorder;
        this.kill = kill;
    }

    public LlmResponse complete(CompleteRequest req) {
        var tenantId = RequestContext.requireTenantId();
        var tenant = RequestContext.requireTenantName();
        var id = ulid();

        var killBlocked = isKilled(tenantId);
        if (killBlocked != null) {
            recordKilled(id, tenantId, req.purpose(), req.realm(), "complete");
            throw killBlocked;
        }

        var decision = routing.resolve(tenant, req.purpose(), req.model());
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
            recorder.insert(new LlmCallRecorder.Row(
                    id, tenantId, req.purpose(), req.realm(),
                    decision.provider(), decision.model(), "complete",
                    pRes.usage().inputTokens(), pRes.usage().outputTokens(),
                    pRes.usage().cacheCreationInputTokens(), pRes.usage().cacheReadInputTokens(),
                    cost, dur, "ok", null));
            return new LlmResponse(pRes.text(), pRes.stopReason(), pRes.usage(),
                    decision.provider(), decision.model(), cost, id);
        } catch (LlmProvider.ProviderException e) {
            var dur = (int) ((System.nanoTime() - start) / 1_000_000);
            recorder.insert(new LlmCallRecorder.Row(
                    id, tenantId, req.purpose(), req.realm(),
                    decision.provider(), decision.model(), "complete",
                    0, 0, 0, 0, 0, dur,
                    e.statusCode() >= 500 ? "error" : "rate_limited",
                    e.errorCode()));
            throw e;
        }
    }

    public LlmResponse vision(VisionRequest req) {
        var tenantId = RequestContext.requireTenantId();
        var tenant = RequestContext.requireTenantName();
        var id = ulid();

        var killBlocked = isKilled(tenantId);
        if (killBlocked != null) {
            recordKilled(id, tenantId, req.purpose(), req.realm(), "vision");
            throw killBlocked;
        }

        var decision = routing.resolve(tenant, req.purpose(), req.model());
        var provider = providers.get(decision.provider());

        var start = System.nanoTime();
        try {
            var pRes = provider.vision(decision.model(),
                    req.max_tokens() == null ? 1024 : req.max_tokens(),
                    req.image().media_type(), req.image().data(), req.prompt());
            var dur = (int) ((System.nanoTime() - start) / 1_000_000);
            var cost = prices.costMicros(decision.model(), pRes.usage());
            recorder.insert(new LlmCallRecorder.Row(
                    id, tenantId, req.purpose(), req.realm(),
                    decision.provider(), decision.model(), "vision",
                    pRes.usage().inputTokens(), pRes.usage().outputTokens(),
                    pRes.usage().cacheCreationInputTokens(), pRes.usage().cacheReadInputTokens(),
                    cost, dur, "ok", null));
            return new LlmResponse(pRes.text(), pRes.stopReason(), pRes.usage(),
                    decision.provider(), decision.model(), cost, id);
        } catch (LlmProvider.ProviderException e) {
            var dur = (int) ((System.nanoTime() - start) / 1_000_000);
            recorder.insert(new LlmCallRecorder.Row(
                    id, tenantId, req.purpose(), req.realm(),
                    decision.provider(), decision.model(), "vision",
                    0, 0, 0, 0, 0, dur,
                    e.statusCode() >= 500 ? "error" : "rate_limited",
                    e.errorCode()));
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
                id, tenantId, purpose, realm,
                "n/a", "n/a", endpoint,
                0, 0, 0, 0, 0, 0, "killed", null));
    }

    private static String ulid() {
        return UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }
}
