package de.vesterion.vistierie.provider;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import de.vesterion.vistierie.pricing.Usage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaudeSubscriptionProviderTest {
    WireMockServer wm;
    ClaudeSubscriptionProvider provider;

    @BeforeEach void up() {
        wm = new WireMockServer(0);
        wm.start();
        WireMock.configureFor("localhost", wm.port());
        var http = RestClient.builder()
                .baseUrl("http://localhost:" + wm.port())
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
        provider = new ClaudeSubscriptionProvider(http);
    }

    @AfterEach void down() { wm.stop(); }

    private static final String OK_BODY = """
            {"text":"hello","stop_reason":"end_turn","model":"claude-opus-4-8",
             "usage":{"input_tokens":10,"output_tokens":4,
                      "cache_creation_input_tokens":0,"cache_read_input_tokens":2}}
            """;

    @Test void completeMapsResponse() {
        stubFor(post(urlEqualTo("/v1/complete")).willReturn(okJson(OK_BODY)));
        var res = provider.complete(new ProviderRequest("claude-opus-4-8", 256, null,
                "be brief", List.of(Map.of("role", "user", "content", "hi")),
                null, null, null));
        assertThat(res.text()).isEqualTo("hello");
        assertThat(res.stopReason()).isEqualTo("end_turn");
        assertThat(res.usage()).isEqualTo(new Usage(10, 4, 0, 2));
        assertThat(res.model()).isEqualTo("claude-opus-4-8");
    }

    @Test void completeSendsVistierieShape() {
        stubFor(post(urlEqualTo("/v1/complete")).willReturn(okJson(OK_BODY)));
        provider.complete(new ProviderRequest("claude-opus-4-8", 256, null,
                "sys", List.of(Map.of("role", "user", "content", "hi")),
                null, null, null));
        verify(postRequestedFor(urlEqualTo("/v1/complete"))
                .withRequestBody(containing("\"model\":\"claude-opus-4-8\""))
                .withRequestBody(containing("\"max_tokens\":256"))
                .withRequestBody(containing("\"system\":\"sys\"")));
    }

    @Test void quota429MapsToSubscriptionExhausted() {
        stubFor(post(urlEqualTo("/v1/complete")).willReturn(aResponse()
                .withStatus(429)
                .withBody("{\"error\":{\"code\":\"subscription_exhausted\",\"message\":\"limit\"}}")));
        assertThatThrownBy(() -> provider.complete(minimalReq()))
                .isInstanceOfSatisfying(LlmProvider.ProviderException.class, e -> {
                    assertThat(e.statusCode()).isEqualTo(429);
                    assertThat(e.errorCode()).isEqualTo("subscription_exhausted");
                });
    }

    @Test void serverErrorMapsTo502() {
        stubFor(post(urlEqualTo("/v1/complete")).willReturn(aResponse()
                .withStatus(500)
                .withBody("{\"error\":{\"code\":\"auth_expired\",\"message\":\"token\"}}")));
        assertThatThrownBy(() -> provider.complete(minimalReq()))
                .isInstanceOfSatisfying(LlmProvider.ProviderException.class, e -> {
                    assertThat(e.statusCode()).isEqualTo(502);
                    assertThat(e.errorCode()).isEqualTo("auth_expired");
                });
    }

    @Test void connectionFailureMapsTo502() {
        wm.stop(); // nothing listening
        assertThatThrownBy(() -> provider.complete(minimalReq()))
                .isInstanceOfSatisfying(LlmProvider.ProviderException.class,
                        e -> assertThat(e.statusCode()).isEqualTo(502));
    }

    @Test void visionBuildsImageBlock() {
        stubFor(post(urlEqualTo("/v1/complete")).willReturn(okJson(OK_BODY)));
        provider.vision("claude-opus-4-8", 100, "image/png", "AAAA", "describe");
        verify(postRequestedFor(urlEqualTo("/v1/complete"))
                .withRequestBody(containing("\"type\":\"image\""))
                .withRequestBody(containing("\"media_type\":\"image/png\""))
                .withRequestBody(containing("\"data\":\"AAAA\"")));
    }

    private ProviderRequest minimalReq() {
        return new ProviderRequest("claude-opus-4-8", 10, null, null,
                List.of(Map.of("role", "user", "content", "hi")), null, null, null);
    }

    @Test void completeSendsEffortWhenSet() {
        stubFor(post(urlEqualTo("/v1/complete")).willReturn(okJson(OK_BODY)));
        provider.complete(new ProviderRequest("claude-opus-4-8", 256, null, null,
                List.of(Map.of("role", "user", "content", "hi")), null, null, null, "off"));
        verify(postRequestedFor(urlEqualTo("/v1/complete"))
                .withRequestBody(containing("\"effort\":\"off\"")));
    }

    @Test void completeOmitsEffortWhenNull() {
        stubFor(post(urlEqualTo("/v1/complete")).willReturn(okJson(OK_BODY)));
        provider.complete(minimalReq());
        verify(postRequestedFor(urlEqualTo("/v1/complete"))
                .withRequestBody(notMatching(".*\"effort\".*")));
    }

    @Test void completeSendsToolsAndSessionId() {
        stubFor(post(urlEqualTo("/v1/complete")).willReturn(okJson(OK_BODY)));
        var tools = List.<Map<String, Object>>of(Map.of(
                "name", "search",
                "description", "search things",
                "input_schema", Map.of("type", "object"),
                "type", "webhook",
                "webhook_url", "http://evil.example/hook",
                "target_agent", "some-agent"));
        provider.complete(new ProviderRequest("claude-opus-4-8", 256, null, null,
                List.of(Map.of("role", "user", "content", "hi")),
                tools, null, Map.of("provider_session_id", "s-1")));
        verify(postRequestedFor(urlEqualTo("/v1/complete"))
                .withRequestBody(containing("\"tools\":[{\"name\":\"search\""))
                .withRequestBody(containing("\"input_schema\":{\"type\":\"object\"}"))
                .withRequestBody(containing("\"session_id\":\"s-1\""))
                .withRequestBody(notMatching(".*webhook_url.*"))
                .withRequestBody(notMatching(".*target_agent.*")));
    }

    @Test void completeMapsToolUseResponse() {
        var toolUseBody = """
                {"text":"","stop_reason":"tool_use","model":"m",
                 "usage":{"input_tokens":0,"output_tokens":0,
                          "cache_creation_input_tokens":0,"cache_read_input_tokens":0},
                 "content_blocks":[{"type":"tool_use","id":"tu1","name":"f","input":{}}],
                 "session_id":"s-9"}
                """;
        stubFor(post(urlEqualTo("/v1/complete")).willReturn(okJson(toolUseBody)));
        var res = provider.complete(minimalReq());
        assertThat(res.stopReason()).isEqualTo("tool_use");
        assertThat(res.contentBlocks().get(0).path("id").asText()).isEqualTo("tu1");
        assertThat(res.sessionId()).isEqualTo("s-9");
    }
}
