package de.vesterion.vistierie.llm;

import de.vesterion.vistierie.agents.Agent;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.audit.LlmCallRecorder;
import de.vesterion.vistierie.budget.BudgetEnforcer;
import de.vesterion.vistierie.auth.RequestContext;
import de.vesterion.vistierie.kill.KillSwitchService;
import de.vesterion.vistierie.llm.dto.CompleteRequest;
import de.vesterion.vistierie.llm.dto.MultiVisionRequest;
import de.vesterion.vistierie.llm.dto.VisionRequest;
import de.vesterion.vistierie.pricing.PriceTable;
import de.vesterion.vistierie.pricing.Usage;
import de.vesterion.vistierie.provider.LlmProvider;
import de.vesterion.vistierie.provider.ProviderRegistry;
import de.vesterion.vistierie.provider.ProviderRequest;
import de.vesterion.vistierie.provider.ProviderResponse;
import de.vesterion.vistierie.routing.RoutingDecision;
import de.vesterion.vistierie.routing.RoutingResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmServiceTest {

    private final RoutingResolver routing = mock(RoutingResolver.class);
    private final ProviderRegistry providers = mock(ProviderRegistry.class);
    private final PriceTable prices = new PriceTable(1.0);
    private final LlmCallRecorder recorder = mock(LlmCallRecorder.class);
    private final KillSwitchService kill = mock(KillSwitchService.class);
    private final LlmProvider provider = mock(LlmProvider.class);
    private final LlmMetrics metrics = mock(LlmMetrics.class);
    private final AgentRepository agents = mock(AgentRepository.class);
    private final BudgetEnforcer budgets = mock(BudgetEnforcer.class);

    private final UUID tenantId = UUID.randomUUID();
    private final String tenantName = "tn-x";
    private final UUID agentId = UUID.randomUUID();
    private final Agent agent = new Agent(agentId, tenantId, "writer", "sys", "test_purpose",
            null, null, 5, 60, "wt", false, 1, Instant.now(), Instant.now(), null, null, null, null, null, null, null);

    private final LlmService svc = new LlmService(routing, providers, prices, recorder, kill, metrics, agents, budgets);

    @BeforeEach void setContext() {
        RequestContext.set(new RequestContext.Principal(tenantId, tenantName, false));
        when(agents.findByName(tenantId, "writer")).thenReturn(java.util.Optional.of(agent));
        when(budgets.checkOrThrow(tenantId, tenantName, agentId, "writer"))
                .thenReturn(new BudgetEnforcer.BudgetCheckResult(1000L, 5000L, 800L, 4000L));
    }

    @AfterEach void clearContext() {
        RequestContext.clear();
    }

    private CompleteRequest completeReq() {
        return new CompleteRequest("writer", "test_purpose", "test_realm", "sys",
                List.of(Map.of("role", "user", "content", "hi")),
                null, null, null);
    }

    private VisionRequest visionReq() {
        return new VisionRequest("writer", "test_purpose", "test_realm",
                new VisionRequest.Image("base64", "image/png", "AAAA"),
                "describe", null, null);
    }

    @Test void completeOkPathRoutesAndRecordsSuccess() {
        when(routing.resolve(eq(tenantName), eq("test_realm"), eq("test_purpose"), eq(null)))
                .thenReturn(new RoutingDecision("anthropic", "claude-haiku-4-5", false));
        when(providers.get("anthropic")).thenReturn(provider);
        when(provider.complete(any())).thenReturn(new ProviderResponse(
                "ok", "end_turn", new Usage(10, 20, 0, 0), "claude-haiku-4-5"));

        var res = svc.complete(completeReq());

        assertThat(res.response().text()).isEqualTo("ok");
        assertThat(res.response().provider()).isEqualTo("anthropic");
        assertThat(res.response().model()).isEqualTo("claude-haiku-4-5");
        assertThat(res.response().cost_micros()).isGreaterThan(0L);
        assertThat(res.response().llm_call_id()).isNotBlank();

        var captor = ArgumentCaptor.forClass(LlmCallRecorder.Row.class);
        verify(recorder).insertWithBody(captor.capture(), any(), any());
        var row = captor.getValue();
        assertThat(row.status()).isEqualTo("ok");
        assertThat(row.endpoint()).isEqualTo("complete");
        assertThat(row.tenantId()).isEqualTo(tenantId);
        assertThat(row.agentId()).isEqualTo(agentId);
        assertThat(row.inputTokens()).isEqualTo(10);
        assertThat(row.outputTokens()).isEqualTo(20);
    }

    @Test void completeUsesDefaultMaxTokensWhenNull() {
        when(routing.resolve(any(), any(), any(), any()))
                .thenReturn(new RoutingDecision("anthropic", "claude-haiku-4-5", false));
        when(providers.get("anthropic")).thenReturn(provider);
        when(provider.complete(any())).thenReturn(new ProviderResponse(
                "ok", "end_turn", new Usage(1, 1, 0, 0), "claude-haiku-4-5"));

        svc.complete(completeReq());

        var captor = ArgumentCaptor.forClass(ProviderRequest.class);
        verify(provider).complete(captor.capture());
        assertThat(captor.getValue().maxTokens()).isEqualTo(1024);
    }

    @Test void completeForwardsDecisionEffortToProvider() {
        when(routing.resolve(any(), any(), any(), any()))
                .thenReturn(new RoutingDecision("claude-subscription", "claude-haiku-4-5",
                        false, null, null, "off"));
        when(providers.get("claude-subscription")).thenReturn(provider);
        when(provider.complete(any())).thenReturn(new ProviderResponse(
                "ok", "end_turn", new Usage(1, 1, 0, 0), "claude-haiku-4-5"));

        svc.complete(completeReq());

        var captor = ArgumentCaptor.forClass(ProviderRequest.class);
        verify(provider).complete(captor.capture());
        assertThat(captor.getValue().effort()).isEqualTo("off");
    }

    @Test void completeBlockedByKillSwitchRecordsKilledAndThrows() {
        var killExc = new KillSwitchService.KilledException("abuse",
                Instant.parse("2026-05-10T13:00:00Z"));
        org.mockito.Mockito.doThrow(killExc).when(kill).check(eq(tenantId));

        assertThatThrownBy(() -> svc.complete(completeReq()))
                .isInstanceOf(KillSwitchService.KilledException.class);

        verify(routing, never()).resolve(any(), any(), any(), any());
        verify(provider, never()).complete(any());

        var captor = ArgumentCaptor.forClass(LlmCallRecorder.Row.class);
        verify(recorder).insert(captor.capture());
        var row = captor.getValue();
        assertThat(row.status()).isEqualTo("killed");
        assertThat(row.endpoint()).isEqualTo("complete");
        assertThat(row.provider()).isEqualTo("n/a");
    }

    @Test void completeProvider5xxRecordedAsErrorAndRethrown() {
        when(routing.resolve(any(), any(), any(), any()))
                .thenReturn(new RoutingDecision("anthropic", "claude-haiku-4-5", false));
        when(providers.get("anthropic")).thenReturn(provider);
        when(provider.complete(any())).thenThrow(
                new LlmProvider.ProviderException(503, "overloaded", "down"));

        assertThatThrownBy(() -> svc.complete(completeReq()))
                .isInstanceOf(LlmProvider.ProviderException.class);

        var captor = ArgumentCaptor.forClass(LlmCallRecorder.Row.class);
        verify(recorder).insertWithBody(captor.capture(), any(), eq(null));
        var row = captor.getValue();
        assertThat(row.status()).isEqualTo("error");
        assertThat(row.errorCode()).isEqualTo("overloaded");
    }

    @Test void completeProvider4xxRecordedAsRateLimited() {
        when(routing.resolve(any(), any(), any(), any()))
                .thenReturn(new RoutingDecision("anthropic", "claude-haiku-4-5", false));
        when(providers.get("anthropic")).thenReturn(provider);
        when(provider.complete(any())).thenThrow(
                new LlmProvider.ProviderException(429, "rate_limit_error", "slow down"));

        assertThatThrownBy(() -> svc.complete(completeReq()))
                .isInstanceOf(LlmProvider.ProviderException.class);

        var captor = ArgumentCaptor.forClass(LlmCallRecorder.Row.class);
        verify(recorder).insertWithBody(captor.capture(), any(), eq(null));
        assertThat(captor.getValue().status()).isEqualTo("rate_limited");
        assertThat(captor.getValue().errorCode()).isEqualTo("rate_limit_error");
    }

    private MultiVisionRequest multiVisionReq() {
        return new MultiVisionRequest("writer", "test_purpose", "test_realm",
                List.of(new MultiVisionRequest.Image("base64", "image/png", "AAAA"),
                        new MultiVisionRequest.Image("base64", "image/png", "BBBB")),
                "describe", null, null);
    }

    @Test void visionMultiOkPathPassesAllImages() {
        when(routing.resolve(any(), any(), any(), any()))
                .thenReturn(new RoutingDecision("anthropic", "claude-haiku-4-5", false));
        when(providers.get("anthropic")).thenReturn(provider);
        var imgCaptor = ArgumentCaptor.forClass(List.class);
        when(provider.visionMulti(eq("claude-haiku-4-5"), eq(1024), imgCaptor.capture(), eq("describe")))
                .thenReturn(new ProviderResponse("two cats", "end_turn",
                        new Usage(8, 4, 0, 0), "claude-haiku-4-5"));

        var res = svc.visionMulti(multiVisionReq());

        assertThat(res.response().text()).isEqualTo("two cats");
        assertThat(imgCaptor.getValue()).hasSize(2);
        @SuppressWarnings("unchecked")
        java.util.List<de.vesterion.vistierie.provider.LlmProvider.ImageInput> captured =
                (java.util.List<de.vesterion.vistierie.provider.LlmProvider.ImageInput>) imgCaptor.getValue();
        assertThat(captured.get(0).base64()).isEqualTo("AAAA");
        assertThat(captured.get(1).base64()).isEqualTo("BBBB");
        assertThat(captured.get(0).mediaType()).isEqualTo("image/png");
        assertThat(captured.get(1).mediaType()).isEqualTo("image/png");
        var captor = ArgumentCaptor.forClass(LlmCallRecorder.Row.class);
        verify(recorder).insertWithBody(captor.capture(), any(), any());
        assertThat(captor.getValue().endpoint()).isEqualTo("vision-multi");
        assertThat(captor.getValue().status()).isEqualTo("ok");
        assertThat(captor.getValue().agentId()).isEqualTo(agentId);
    }

    @Test void visionMultiBlockedByKillSwitch() {
        org.mockito.Mockito.doThrow(new KillSwitchService.KilledException("x", Instant.now()))
                .when(kill).check(eq(tenantId));

        assertThatThrownBy(() -> svc.visionMulti(multiVisionReq()))
                .isInstanceOf(KillSwitchService.KilledException.class);

        var captor = ArgumentCaptor.forClass(LlmCallRecorder.Row.class);
        verify(recorder).insert(captor.capture());
        assertThat(captor.getValue().endpoint()).isEqualTo("vision-multi");
        assertThat(captor.getValue().status()).isEqualTo("killed");
        verify(provider, never()).visionMulti(any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any());
    }

    @Test void visionMultiProvider5xx() {
        when(routing.resolve(any(), any(), any(), any()))
                .thenReturn(new RoutingDecision("anthropic", "claude-haiku-4-5", false));
        when(providers.get("anthropic")).thenReturn(provider);
        when(provider.visionMulti(any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenThrow(new LlmProvider.ProviderException(500, "internal", "boom"));

        assertThatThrownBy(() -> svc.visionMulti(multiVisionReq()))
                .isInstanceOf(LlmProvider.ProviderException.class);

        var captor = ArgumentCaptor.forClass(LlmCallRecorder.Row.class);
        verify(recorder).insertWithBody(captor.capture(), any(), eq(null));
        assertThat(captor.getValue().status()).isEqualTo("error");
    }

    @Test void visionMultiProvider4xxRecordedAsRateLimited() {
        when(routing.resolve(any(), any(), any(), any()))
                .thenReturn(new RoutingDecision("anthropic", "claude-haiku-4-5", false));
        when(providers.get("anthropic")).thenReturn(provider);
        when(provider.visionMulti(any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenThrow(new LlmProvider.ProviderException(429, "rate_limit_error", "slow down"));

        assertThatThrownBy(() -> svc.visionMulti(multiVisionReq()))
                .isInstanceOf(LlmProvider.ProviderException.class);

        var captor = ArgumentCaptor.forClass(LlmCallRecorder.Row.class);
        verify(recorder).insertWithBody(captor.capture(), any(), eq(null));
        assertThat(captor.getValue().status()).isEqualTo("rate_limited");
        assertThat(captor.getValue().endpoint()).isEqualTo("vision-multi");
        assertThat(captor.getValue().errorCode()).isEqualTo("rate_limit_error");
    }

    @Test void visionOkPath() {
        when(routing.resolve(any(), any(), any(), any()))
                .thenReturn(new RoutingDecision("anthropic", "claude-haiku-4-5", false));
        when(providers.get("anthropic")).thenReturn(provider);
        when(provider.vision(eq("claude-haiku-4-5"), eq(1024),
                eq("image/png"), eq("AAAA"), eq("describe")))
                .thenReturn(new ProviderResponse("a cat", "end_turn",
                        new Usage(5, 5, 0, 0), "claude-haiku-4-5"));

        var res = svc.vision(visionReq());

        assertThat(res.response().text()).isEqualTo("a cat");
        var captor = ArgumentCaptor.forClass(LlmCallRecorder.Row.class);
        verify(recorder).insertWithBody(captor.capture(), any(), any());
        assertThat(captor.getValue().endpoint()).isEqualTo("vision");
        assertThat(captor.getValue().status()).isEqualTo("ok");
        assertThat(captor.getValue().agentId()).isEqualTo(agentId);
    }

    @Test void visionBlockedByKillSwitch() {
        org.mockito.Mockito.doThrow(new KillSwitchService.KilledException("x", Instant.now()))
                .when(kill).check(eq(tenantId));

        assertThatThrownBy(() -> svc.vision(visionReq()))
                .isInstanceOf(KillSwitchService.KilledException.class);

        var captor = ArgumentCaptor.forClass(LlmCallRecorder.Row.class);
        verify(recorder).insert(captor.capture());
        assertThat(captor.getValue().endpoint()).isEqualTo("vision");
        assertThat(captor.getValue().status()).isEqualTo("killed");
        verify(provider, never()).vision(any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any());
    }

    @Test void visionProvider5xx() {
        when(routing.resolve(any(), any(), any(), any()))
                .thenReturn(new RoutingDecision("anthropic", "claude-haiku-4-5", false));
        when(providers.get("anthropic")).thenReturn(provider);
        when(provider.vision(any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any()))
                .thenThrow(new LlmProvider.ProviderException(500, "internal", "boom"));

        assertThatThrownBy(() -> svc.vision(visionReq()))
                .isInstanceOf(LlmProvider.ProviderException.class);

        var captor = ArgumentCaptor.forClass(LlmCallRecorder.Row.class);
        verify(recorder).insertWithBody(captor.capture(), any(), eq(null));
        assertThat(captor.getValue().status()).isEqualTo("error");
    }

    @Test void fallbackOn429RetriesOnFallbackProvider() {
        var fallbackProvider = mock(LlmProvider.class);
        when(routing.resolve(eq(tenantName), eq("test_realm"), eq("test_purpose"), eq(null)))
                .thenReturn(new RoutingDecision("claude-subscription", "claude-opus-4-8", false,
                        "anthropic", "claude-haiku-4-5"));
        when(providers.get("claude-subscription")).thenReturn(provider);
        when(providers.get("anthropic")).thenReturn(fallbackProvider);
        when(provider.complete(any())).thenThrow(
                new LlmProvider.ProviderException(429, "subscription_exhausted", "limit"));
        when(fallbackProvider.complete(any())).thenReturn(new ProviderResponse(
                "ok", "end_turn", new Usage(10, 20, 0, 0), "claude-haiku-4-5"));

        var res = svc.complete(completeReq());

        assertThat(res.response().provider()).isEqualTo("anthropic");
        assertThat(res.response().model()).isEqualTo("claude-haiku-4-5");
        verify(metrics).recordFallback("claude-subscription", "anthropic", "rate_limited");
        // two audit rows: failed primary + successful fallback
        var rows = ArgumentCaptor.forClass(LlmCallRecorder.Row.class);
        verify(recorder, org.mockito.Mockito.times(2)).insertWithBody(rows.capture(), any(), any());
        assertThat(rows.getAllValues().get(0).status()).isEqualTo("rate_limited");
        assertThat(rows.getAllValues().get(1).status()).isEqualTo("ok");
        assertThat(rows.getAllValues().get(0).id()).isNotEqualTo(rows.getAllValues().get(1).id());
    }

    @Test void no4xxFallback() {
        var fallbackProvider = mock(LlmProvider.class);
        when(routing.resolve(any(), any(), any(), any())).thenReturn(
                new RoutingDecision("claude-subscription", "claude-opus-4-8", false,
                        "anthropic", "claude-haiku-4-5"));
        when(providers.get("claude-subscription")).thenReturn(provider);
        when(provider.complete(any())).thenThrow(
                new LlmProvider.ProviderException(400, "invalid_request", "bad"));

        assertThatThrownBy(() -> svc.complete(completeReq()))
                .isInstanceOf(LlmProvider.ProviderException.class);
        verify(providers, never()).get("anthropic");
    }

    @Test void unsupportedOperationTriggersFallback() {
        var fallbackProvider = mock(LlmProvider.class);
        when(routing.resolve(any(), any(), any(), any())).thenReturn(
                new RoutingDecision("claude-subscription", "claude-opus-4-8", false,
                        "anthropic", "claude-haiku-4-5"));
        when(providers.get("claude-subscription")).thenReturn(provider);
        when(providers.get("anthropic")).thenReturn(fallbackProvider);
        when(provider.complete(any())).thenThrow(new UnsupportedOperationException("nope"));
        when(fallbackProvider.complete(any())).thenReturn(new ProviderResponse(
                "ok", "end_turn", new Usage(1, 1, 0, 0), "claude-haiku-4-5"));

        var res = svc.complete(completeReq());
        assertThat(res.response().provider()).isEqualTo("anthropic");
        verify(metrics).recordFallback("claude-subscription", "anthropic", "unsupported");
    }

    @Test void fallbackFailurePropagates() {
        var fallbackProvider = mock(LlmProvider.class);
        when(routing.resolve(any(), any(), any(), any())).thenReturn(
                new RoutingDecision("claude-subscription", "claude-opus-4-8", false,
                        "anthropic", "claude-haiku-4-5"));
        when(providers.get("claude-subscription")).thenReturn(provider);
        when(providers.get("anthropic")).thenReturn(fallbackProvider);
        when(provider.complete(any())).thenThrow(
                new LlmProvider.ProviderException(429, "subscription_exhausted", "limit"));
        when(fallbackProvider.complete(any())).thenThrow(
                new LlmProvider.ProviderException(500, "api_error", "down"));

        assertThatThrownBy(() -> svc.complete(completeReq()))
                .isInstanceOfSatisfying(LlmProvider.ProviderException.class,
                        e -> assertThat(e.statusCode()).isEqualTo(500));
    }

    @Test void subscriptionCallBooksZeroCostAndShadowCost() {
        when(routing.resolve(any(), any(), any(), any())).thenReturn(
                new RoutingDecision("claude-subscription", "claude-opus-4-8", false, null, null));
        when(providers.get("claude-subscription")).thenReturn(provider);
        when(provider.complete(any())).thenReturn(new ProviderResponse(
                "ok", "end_turn", new Usage(1_000_000, 0, 0, 0), "claude-opus-4-8"));

        var res = svc.complete(completeReq());

        assertThat(res.response().cost_micros()).isZero();
        var rows = ArgumentCaptor.forClass(LlmCallRecorder.Row.class);
        verify(recorder).insertWithBody(rows.capture(), any(), any());
        // 1M input tokens of claude-opus-4-8 = 13_800_000 micros (PriceTable)
        assertThat(rows.getValue().costMicros()).isZero();
        assertThat(rows.getValue().shadowCostMicros()).isEqualTo(13_800_000L);
        verify(metrics).recordShadowCost("claude-subscription", "claude-opus-4-8", "complete", 13_800_000L);
    }

    @Test void subscriptionCallWithUnknownModelHasNullShadowCost() {
        when(routing.resolve(any(), any(), any(), any())).thenReturn(
                new RoutingDecision("claude-subscription", "some-unknown-model", false, null, null));
        when(providers.get("claude-subscription")).thenReturn(provider);
        when(provider.complete(any())).thenReturn(new ProviderResponse(
                "ok", "end_turn", new Usage(10, 10, 0, 0), "some-unknown-model"));

        var res = svc.complete(completeReq());
        assertThat(res.response().cost_micros()).isZero();
        var rows = ArgumentCaptor.forClass(LlmCallRecorder.Row.class);
        verify(recorder).insertWithBody(rows.capture(), any(), any());
        assertThat(rows.getValue().shadowCostMicros()).isNull();
    }

    @Test void auditWriteFailureOnSuccessPathDoesNotFailTheCall() {
        // Finding #11: the provider call already succeeded (and was billed). A failure in the
        // post-success audit write must NOT propagate as a 500 for a successful, charged call.
        when(routing.resolve(any(), any(), any(), any()))
                .thenReturn(new RoutingDecision("anthropic", "claude-haiku-4-5", false));
        when(providers.get("anthropic")).thenReturn(provider);
        when(provider.complete(any())).thenReturn(new ProviderResponse(
                "ok", "end_turn", new Usage(10, 20, 0, 0), "claude-haiku-4-5"));
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(recorder).insertWithBody(any(), any(), any());

        var res = svc.complete(completeReq());

        assertThat(res.response().text()).isEqualTo("ok");
        assertThat(res.response().cost_micros()).isGreaterThan(0L);
        assertThat(res.response().llm_call_id()).isNotBlank();
    }

    @Test void apiProviderCallHasNullShadowCost() {
        when(routing.resolve(any(), any(), any(), any())).thenReturn(
                new RoutingDecision("anthropic", "claude-haiku-4-5", false, null, null));
        when(providers.get("anthropic")).thenReturn(provider);
        when(provider.complete(any())).thenReturn(new ProviderResponse(
                "ok", "end_turn", new Usage(10, 20, 0, 0), "claude-haiku-4-5"));

        var res = svc.complete(completeReq());
        assertThat(res.response().cost_micros()).isGreaterThan(0);
        var rows = ArgumentCaptor.forClass(LlmCallRecorder.Row.class);
        verify(recorder).insertWithBody(rows.capture(), any(), any());
        assertThat(rows.getValue().shadowCostMicros()).isNull();
    }
}
