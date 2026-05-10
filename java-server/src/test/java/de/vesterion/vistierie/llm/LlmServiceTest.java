package de.vesterion.vistierie.llm;

import de.vesterion.vistierie.audit.LlmCallRecorder;
import de.vesterion.vistierie.auth.RequestContext;
import de.vesterion.vistierie.kill.KillSwitchService;
import de.vesterion.vistierie.llm.dto.CompleteRequest;
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

    private final UUID tenantId = UUID.randomUUID();
    private final String tenantName = "tn-x";

    private final LlmService svc = new LlmService(routing, providers, prices, recorder, kill, metrics);

    @BeforeEach void setContext() {
        RequestContext.set(new RequestContext.Principal(tenantId, tenantName, false));
    }

    @AfterEach void clearContext() {
        RequestContext.clear();
    }

    private CompleteRequest completeReq() {
        return new CompleteRequest("test_purpose", "test_realm", "sys",
                List.of(Map.of("role", "user", "content", "hi")),
                null, null, null);
    }

    private VisionRequest visionReq() {
        return new VisionRequest("test_purpose", "test_realm",
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

        assertThat(res.text()).isEqualTo("ok");
        assertThat(res.provider()).isEqualTo("anthropic");
        assertThat(res.model()).isEqualTo("claude-haiku-4-5");
        assertThat(res.cost_micros()).isGreaterThan(0L);
        assertThat(res.llm_call_id()).isNotBlank();

        var captor = ArgumentCaptor.forClass(LlmCallRecorder.Row.class);
        verify(recorder).insertWithBody(captor.capture(), any(), any());
        var row = captor.getValue();
        assertThat(row.status()).isEqualTo("ok");
        assertThat(row.endpoint()).isEqualTo("complete");
        assertThat(row.tenantId()).isEqualTo(tenantId);
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

    @Test void visionOkPath() {
        when(routing.resolve(any(), any(), any(), any()))
                .thenReturn(new RoutingDecision("anthropic", "claude-haiku-4-5", false));
        when(providers.get("anthropic")).thenReturn(provider);
        when(provider.vision(eq("claude-haiku-4-5"), eq(1024),
                eq("image/png"), eq("AAAA"), eq("describe")))
                .thenReturn(new ProviderResponse("a cat", "end_turn",
                        new Usage(5, 5, 0, 0), "claude-haiku-4-5"));

        var res = svc.vision(visionReq());

        assertThat(res.text()).isEqualTo("a cat");
        var captor = ArgumentCaptor.forClass(LlmCallRecorder.Row.class);
        verify(recorder).insertWithBody(captor.capture(), any(), any());
        assertThat(captor.getValue().endpoint()).isEqualTo("vision");
        assertThat(captor.getValue().status()).isEqualTo("ok");
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
}
