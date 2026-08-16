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
import de.vesterion.vistierie.provider.ClaudeSubscriptionProvider;
import de.vesterion.vistierie.provider.LlmProvider;
import de.vesterion.vistierie.provider.ProviderRegistry;
import de.vesterion.vistierie.provider.ProviderRequest;
import de.vesterion.vistierie.provider.ProviderResponse;
import de.vesterion.vistierie.provider.SubscriptionCooldown;
import de.vesterion.vistierie.routing.RoutingDecision;
import de.vesterion.vistierie.routing.RoutingResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

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

@ExtendWith(OutputCaptureExtension.class)
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
    private final SubscriptionCooldown cooldown = new SubscriptionCooldown(3600);

    private final UUID tenantId = UUID.randomUUID();
    private final String tenantName = "tn-x";
    private final UUID agentId = UUID.randomUUID();
    private final Agent agent = new Agent(agentId, tenantId, "writer", "sys", "test_purpose",
            null, null, 5, 60, "wt", false, 1, Instant.now(), Instant.now(), null, null, null, null, null, null, null);

    private final LlmService svc = new LlmService(routing, providers, prices, recorder, kill, metrics, agents, budgets, cooldown);

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
                null, null, null, null, null);
    }

    private ProviderRequest captureProviderRequestFor(CompleteRequest req) {
        when(routing.resolve(eq(tenantName), eq("test_realm"), eq("test_purpose"), eq(null)))
                .thenReturn(new RoutingDecision("anthropic", "claude-haiku-4-5", false));
        when(providers.get("anthropic")).thenReturn(provider);
        when(provider.complete(any())).thenReturn(new ProviderResponse(
                "ok", "end_turn", new Usage(10, 20, 0, 0), "claude-haiku-4-5"));

        svc.complete(req);

        var captor = ArgumentCaptor.forClass(ProviderRequest.class);
        verify(provider).complete(captor.capture());
        return captor.getValue();
    }

    @Test void completeWithoutToolsPassesNullToolsAndNullToolChoice() {
        var captured = captureProviderRequestFor(completeReq());
        assertThat(captured.tools()).isNull();
        assertThat(captured.toolChoice()).isNull();
    }

    @Test void completeForwardsToolsAndToolChoiceVerbatim() {
        var tool = Map.<String, Object>of(
                "name", "submit_mailings",
                "description", "Deliver the grouping.",
                "input_schema", Map.of("type", "object"));
        var choice = Map.<String, Object>of("type", "tool", "name", "submit_mailings");
        var captured = captureProviderRequestFor(new CompleteRequest(
                "writer", "test_purpose", "test_realm", "sys",
                List.of(Map.of("role", "user", "content", "hi")),
                null, null, null, List.of(tool), choice));
        assertThat(captured.tools()).containsExactly(tool);
        assertThat(captured.toolChoice()).isEqualTo(choice);
    }

    /** Regression guard for finding #2: Bedrock/OpenAiCompatible synthesize content_blocks
     *  unconditionally, so LlmService itself must drop them for toolless callers instead of
     *  trusting the provider to only populate the field when tools were offered. */
    @Test void completeWithoutToolsDropsProviderSuppliedContentBlocks() {
        when(routing.resolve(eq(tenantName), eq("test_realm"), eq("test_purpose"), eq(null)))
                .thenReturn(new RoutingDecision("anthropic", "claude-haiku-4-5", false));
        when(providers.get("anthropic")).thenReturn(provider);
        var mapper = new tools.jackson.databind.ObjectMapper();
        var blocks = mapper.createArrayNode();
        blocks.addObject().put("type", "text").put("text", "ok");
        when(provider.complete(any())).thenReturn(new ProviderResponse(
                "ok", "end_turn", new Usage(10, 20, 0, 0), "claude-haiku-4-5", blocks));

        var res = svc.complete(completeReq());

        assertThat(res.response().content_blocks()).isNull();
    }

    @Test void completeWithToolsKeepsProviderSuppliedContentBlocks() {
        var tool = Map.<String, Object>of(
                "name", "submit_mailings",
                "description", "Deliver the grouping.",
                "input_schema", Map.of("type", "object"));
        var choice = Map.<String, Object>of("type", "tool", "name", "submit_mailings");
        when(routing.resolve(eq(tenantName), eq("test_realm"), eq("test_purpose"), eq(null)))
                .thenReturn(new RoutingDecision("anthropic", "claude-haiku-4-5", false));
        when(providers.get("anthropic")).thenReturn(provider);
        var mapper = new tools.jackson.databind.ObjectMapper();
        var blocks = mapper.createArrayNode();
        blocks.addObject().put("type", "tool_use").put("id", "tu1").put("name", "submit_mailings");
        when(provider.complete(any())).thenReturn(new ProviderResponse(
                "", "tool_use", new Usage(10, 20, 0, 0), "claude-haiku-4-5", blocks));

        var res = svc.complete(new CompleteRequest(
                "writer", "test_purpose", "test_realm", "sys",
                List.of(Map.of("role", "user", "content", "hi")),
                null, null, null, List.of(tool), choice));

        assertThat(res.response().content_blocks()).isNotNull();
        assertThat(res.response().content_blocks().get(0).path("id").asText()).isEqualTo("tu1");
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
        verify(recorder).insertWithBody(captor.capture(), any(), any(ProviderResponse.class));
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
        // F1: the failure row's response text is the ProviderException's message ("down") — the
        // upstream error text that would otherwise be lost outside the application log.
        verify(recorder).insertWithBody(captor.capture(), any(), eq("down"));
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
        verify(recorder).insertWithBody(captor.capture(), any(), eq("slow down"));
        assertThat(captor.getValue().status()).isEqualTo("rate_limited");
        assertThat(captor.getValue().errorCode()).isEqualTo("rate_limit_error");
    }

    @Test void completeProvider400RecordedAsError() {
        // sc == 429, nicht >= 500: seit dem upstream_api_error-Passthrough ist von diesem
        // Provider erstmals ein 4xx != 429 erreichbar. Ohne diesen Test bliebe eine Regression
        // auf die alte ">= 500"-Formel unentdeckt, da alle uebrigen rate_limited-Assertions
        // (:192, :272, :347) ausschliesslich 429 verwenden — dem einzigen Statuscode, bei dem
        // beide Formeln uebereinstimmen.
        when(routing.resolve(any(), any(), any(), any()))
                .thenReturn(new RoutingDecision("anthropic", "claude-haiku-4-5", false));
        when(providers.get("anthropic")).thenReturn(provider);
        when(provider.complete(any())).thenThrow(
                new LlmProvider.ProviderException(400, "upstream_api_error", "bad request"));

        assertThatThrownBy(() -> svc.complete(completeReq()))
                .isInstanceOf(LlmProvider.ProviderException.class);

        var captor = ArgumentCaptor.forClass(LlmCallRecorder.Row.class);
        verify(recorder).insertWithBody(captor.capture(), any(), eq("bad request"));
        assertThat(captor.getValue().status()).isEqualTo("error");
        assertThat(captor.getValue().errorCode()).isEqualTo("upstream_api_error");
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
        verify(recorder).insertWithBody(captor.capture(), any(), any(ProviderResponse.class));
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
        verify(recorder).insertWithBody(captor.capture(), any(), eq("boom"));
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
        verify(recorder).insertWithBody(captor.capture(), any(), eq("slow down"));
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
        verify(recorder).insertWithBody(captor.capture(), any(), any(ProviderResponse.class));
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
        verify(recorder).insertWithBody(captor.capture(), any(), eq("boom"));
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
        // two audit rows: failed primary (String overload, F1: carries the upstream message) +
        // successful fallback (ProviderResponse overload). The overloads are distinct methods to
        // Mockito, so each is captured/verified separately.
        var failRow = ArgumentCaptor.forClass(LlmCallRecorder.Row.class);
        verify(recorder).insertWithBody(failRow.capture(), any(), eq("limit"));
        var okRow = ArgumentCaptor.forClass(LlmCallRecorder.Row.class);
        verify(recorder).insertWithBody(okRow.capture(), any(), any(ProviderResponse.class));
        assertThat(failRow.getValue().status()).isEqualTo("rate_limited");
        assertThat(okRow.getValue().status()).isEqualTo("ok");
        assertThat(failRow.getValue().id()).isNotEqualTo(okRow.getValue().id());
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
        verify(recorder).insertWithBody(rows.capture(), any(), any(ProviderResponse.class));
        // 1M input tokens of claude-opus-4-8 = 4_600_000 micros (PriceTable, 5 $ @ 0,92)
        assertThat(rows.getValue().costMicros()).isZero();
        assertThat(rows.getValue().shadowCostMicros()).isEqualTo(4_600_000L);
        verify(metrics).recordShadowCost("claude-subscription", "claude-opus-4-8", "complete", 4_600_000L);
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
        verify(recorder).insertWithBody(rows.capture(), any(), any(ProviderResponse.class));
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
                .when(recorder).insertWithBody(any(), any(), any(ProviderResponse.class));

        var res = svc.complete(completeReq());

        assertThat(res.response().text()).isEqualTo("ok");
        assertThat(res.response().cost_micros()).isGreaterThan(0L);
        assertThat(res.response().llm_call_id()).isNotBlank();
    }

    @Test void completeOkPathLogsProviderModelTokensAndCost(CapturedOutput output) {
        when(routing.resolve(eq(tenantName), eq("test_realm"), eq("test_purpose"), eq(null)))
                .thenReturn(new RoutingDecision("anthropic", "claude-haiku-4-5", false));
        when(providers.get("anthropic")).thenReturn(provider);
        when(provider.complete(any())).thenReturn(new ProviderResponse(
                "ok", "end_turn", new Usage(10, 20, 0, 0), "claude-haiku-4-5"));

        svc.complete(completeReq());

        assertThat(output.getOut()).contains("LLM call")
                .contains("status=ok")
                .contains("provider=anthropic")
                .contains("model=claude-haiku-4-5")
                .contains("in=10")
                .contains("out=20");
    }

    @Test void completeProvider5xxLogsFailureWarning(CapturedOutput output) {
        when(routing.resolve(any(), any(), any(), any()))
                .thenReturn(new RoutingDecision("anthropic", "claude-haiku-4-5", false));
        when(providers.get("anthropic")).thenReturn(provider);
        when(provider.complete(any())).thenThrow(
                new LlmProvider.ProviderException(503, "overloaded", "down"));

        assertThatThrownBy(() -> svc.complete(completeReq()))
                .isInstanceOf(LlmProvider.ProviderException.class);

        assertThat(output.getOut()).contains("LLM call FAILED")
                .contains("provider=anthropic")
                .contains("model=claude-haiku-4-5")
                .contains("status=error")
                .contains("error=overloaded");
    }

    @Test void skipsSubscriptionStraightToFallbackWhileCooling() {
        cooldown.open(Instant.now());
        var sub = mock(LlmProvider.class);
        var fb = mock(LlmProvider.class);
        when(providers.get(ClaudeSubscriptionProvider.NAME)).thenReturn(sub);
        when(providers.get("bedrock")).thenReturn(fb);
        when(routing.resolve(any(), any(), any(), any())).thenReturn(new RoutingDecision(
                ClaudeSubscriptionProvider.NAME, "claude-opus-4-8", true, "bedrock", "eu.anthropic.claude-sonnet-4-6"));
        when(fb.complete(any())).thenReturn(new ProviderResponse(
                "ok", "end_turn", new Usage(1, 1, 0, 0), "eu.anthropic.claude-sonnet-4-6"));

        svc.complete(completeReq());

        verify(sub, never()).complete(any());
        verify(fb).complete(any());
        verify(metrics).recordFallback(ClaudeSubscriptionProvider.NAME, "bedrock", "subscription_cooldown");
    }

    @Test void subscription429OpensCooldownAndFallsOver() {
        var sub = mock(LlmProvider.class);
        var fb = mock(LlmProvider.class);
        when(providers.get(ClaudeSubscriptionProvider.NAME)).thenReturn(sub);
        when(providers.get("bedrock")).thenReturn(fb);
        when(routing.resolve(any(), any(), any(), any())).thenReturn(new RoutingDecision(
                ClaudeSubscriptionProvider.NAME, "claude-opus-4-8", true, "bedrock", "eu.anthropic.claude-sonnet-4-6"));
        when(sub.complete(any())).thenThrow(new LlmProvider.ProviderException(429, "subscription_exhausted", "limit"));
        when(fb.complete(any())).thenReturn(new ProviderResponse(
                "ok", "end_turn", new Usage(1, 1, 0, 0), "eu.anthropic.claude-sonnet-4-6"));

        svc.complete(completeReq());

        assertThat(cooldown.cooling(Instant.now())).isTrue();
        verify(fb).complete(any());
    }

    @Test void non_subscription_provider_429_does_not_open_cooldown() {
        // Isolates the provider-name conjunct: errorCode matches but provider isn't claude-subscription.
        var bed = mock(LlmProvider.class);
        when(providers.get("bedrock")).thenReturn(bed);
        when(routing.resolve(any(), any(), any(), any())).thenReturn(new RoutingDecision(
                "bedrock", "eu.anthropic.claude-sonnet-4-6", true, null, null));
        when(bed.complete(any())).thenThrow(new LlmProvider.ProviderException(429, "subscription_exhausted", "x"));

        assertThatThrownBy(() -> svc.complete(completeReq())).isInstanceOf(LlmProvider.ProviderException.class);
        assertThat(cooldown.cooling(Instant.now())).isFalse();
    }

    @Test void subscription_non_exhausted_429_does_not_open_cooldown() {
        // Isolates the errorCode conjunct: provider matches but errorCode isn't subscription_exhausted.
        var sub = mock(LlmProvider.class);
        var fb = mock(LlmProvider.class);
        when(providers.get(ClaudeSubscriptionProvider.NAME)).thenReturn(sub);
        when(providers.get("bedrock")).thenReturn(fb);
        when(routing.resolve(any(), any(), any(), any())).thenReturn(new RoutingDecision(
                ClaudeSubscriptionProvider.NAME, "claude-opus-4-8", true, "bedrock", "eu.anthropic.claude-sonnet-4-6"));
        when(sub.complete(any())).thenThrow(new LlmProvider.ProviderException(429, "rate_limit_exceeded", "rl"));
        when(fb.complete(any())).thenReturn(new ProviderResponse(
                "ok", "end_turn", new Usage(1, 1, 0, 0), "eu.anthropic.claude-sonnet-4-6"));

        svc.complete(completeReq());

        assertThat(cooldown.cooling(Instant.now())).isFalse();
        verify(fb).complete(any());
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
        verify(recorder).insertWithBody(rows.capture(), any(), any(ProviderResponse.class));
        assertThat(rows.getValue().shadowCostMicros()).isNull();
    }
}
