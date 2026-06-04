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

class AnthropicProviderTest {
    WireMockServer wm;
    AnthropicProvider provider;

    @BeforeEach void up() {
        wm = new WireMockServer(0);
        wm.start();
        WireMock.configureFor("localhost", wm.port());
        var http = RestClient.builder()
                .baseUrl("http://localhost:" + wm.port())
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
        provider = new AnthropicProvider(http, "test-key", 60);
    }

    @AfterEach void down() { wm.stop(); }

    @Test void completeReturnsTextAndUsage() {
        stubFor(post(urlEqualTo("/v1/messages")).willReturn(okJson("""
                {
                  "id": "msg_01",
                  "type": "message",
                  "role": "assistant",
                  "model": "claude-haiku-4-5",
                  "content": [{"type":"text","text":"hi there"}],
                  "stop_reason": "end_turn",
                  "usage": {
                    "input_tokens": 12,
                    "output_tokens": 5,
                    "cache_creation_input_tokens": 0,
                    "cache_read_input_tokens": 0
                  }
                }
                """)));

        var req = new ProviderRequest(
                "claude-haiku-4-5", 256, 0.2,
                "you are a poet",
                List.of(Map.of("role", "user", "content", "say hi")),
                null, null, null
        );
        var res = provider.complete(req);
        assertThat(res.text()).isEqualTo("hi there");
        assertThat(res.stopReason()).isEqualTo("end_turn");
        assertThat(res.usage()).isEqualTo(new Usage(12, 5, 0, 0));
    }

    @Test void visionAddsImageBlock() {
        stubFor(post(urlEqualTo("/v1/messages")).willReturn(okJson("""
                {"id":"m","type":"message","role":"assistant","model":"claude-haiku-4-5",
                 "content":[{"type":"text","text":"a chart"}],"stop_reason":"end_turn",
                 "usage":{"input_tokens":3,"output_tokens":2,
                          "cache_creation_input_tokens":0,"cache_read_input_tokens":0}}
                """)));

        var res = provider.vision("claude-haiku-4-5", 256,
                "image/png", "AAAA", "describe");

        assertThat(res.text()).isEqualTo("a chart");
        verify(postRequestedFor(urlEqualTo("/v1/messages"))
                .withRequestBody(containing("\"type\":\"image\""))
                .withRequestBody(containing("\"media_type\":\"image/png\""))
                .withHeader("x-api-key", equalTo("test-key"))
                .withHeader("anthropic-version", equalTo("2023-06-01")));
    }

    @Test void visionMultiAddsImageBlockPerImage() {
        stubFor(post(urlEqualTo("/v1/messages")).willReturn(okJson("""
                {"id":"m","type":"message","role":"assistant","model":"claude-haiku-4-5",
                 "content":[{"type":"text","text":"two charts"}],"stop_reason":"end_turn",
                 "usage":{"input_tokens":6,"output_tokens":2,
                          "cache_creation_input_tokens":0,"cache_read_input_tokens":0}}
                """)));

        var images = List.of(
                new LlmProvider.ImageInput("image/png", "AAAA"),
                new LlmProvider.ImageInput("image/jpeg", "BBBB"));
        var res = provider.visionMulti("claude-haiku-4-5", 256, images, "describe both");

        assertThat(res.text()).isEqualTo("two charts");
        verify(postRequestedFor(urlEqualTo("/v1/messages"))
                .withRequestBody(containing("\"data\":\"AAAA\""))
                .withRequestBody(containing("\"media_type\":\"image/jpeg\""))
                .withRequestBody(containing("\"data\":\"BBBB\""))
                .withRequestBody(containing("\"text\":\"describe both\""))
                .withHeader("x-api-key", equalTo("test-key")));
    }

    @Test void providerErrorMaps() {
        stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse().withStatus(529)
                .withBody("{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}")));
        var req = new ProviderRequest("claude-haiku-4-5", 10, null, null,
                List.of(Map.of("role", "user", "content", "hi")), null, null, null);
        try {
            provider.complete(req);
            org.junit.jupiter.api.Assertions.fail("expected ProviderException");
        } catch (LlmProvider.ProviderException e) {
            assertThat(e.statusCode()).isEqualTo(529);
            assertThat(e.errorCode()).isEqualTo("overloaded_error");
        }
    }

    @Test void toolUseRoundTrip() {
        stubFor(post(urlEqualTo("/v1/messages")).willReturn(okJson("""
                {"id":"m","type":"message","role":"assistant","model":"claude-haiku-4-5",
                 "content":[
                   {"type":"text","text":"thinking"},
                   {"type":"tool_use","id":"toolu_1","name":"cell.read","input":{"id":"c1"}}
                 ],
                 "stop_reason":"tool_use",
                 "usage":{"input_tokens":10,"output_tokens":4,
                          "cache_creation_input_tokens":0,"cache_read_input_tokens":0}}
                """)));
        var tools = java.util.List.of(java.util.Map.<String, Object>of(
                "name", "cell.read",
                "description", "read",
                "input_schema", java.util.Map.of("type", "object",
                        "properties", java.util.Map.of("id", java.util.Map.of("type", "string")),
                        "required", java.util.List.of("id"))
        ));
        var req = new ProviderRequest("claude-haiku-4-5", 256, null, "system",
                java.util.List.of(java.util.Map.of("role", "user", "content", "find c1")),
                tools, null, null);
        var res = provider.complete(req);
        assertThat(res.stopReason()).isEqualTo("tool_use");
        verify(postRequestedFor(urlEqualTo("/v1/messages"))
                .withRequestBody(containing("\"name\":\"cell.read\""))
                .withRequestBody(containing("\"tools\":")));
    }
}
