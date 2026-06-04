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

class OpenAiCompatibleProviderTest {
    WireMockServer wm;
    OpenAiCompatibleProvider provider;

    @BeforeEach void up() {
        wm = new WireMockServer(0);
        wm.start();
        WireMock.configureFor("localhost", wm.port());
        var http = RestClient.builder()
                .baseUrl("http://localhost:" + wm.port())
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
        provider = new OpenAiCompatibleProvider("openai", http, "test-key");
    }

    @AfterEach void down() { wm.stop(); }

    @Test void completeReturnsTextAndMappedUsage() {
        stubFor(post(urlEqualTo("/chat/completions")).willReturn(okJson("""
                {
                  "id": "chatcmpl-1",
                  "model": "gpt-4o-mini-2024-07-18",
                  "choices": [{
                    "index": 0,
                    "finish_reason": "stop",
                    "message": {"role":"assistant","content":"hi there"}
                  }],
                  "usage": {
                    "prompt_tokens": 12,
                    "completion_tokens": 5,
                    "total_tokens": 17
                  }
                }
                """)));

        var req = new ProviderRequest(
                "gpt-4o-mini", 256, 0.2, "you are a poet",
                List.of(Map.of("role", "user", "content", "say hi")),
                null, null, null);
        var res = provider.complete(req);

        assertThat(res.text()).isEqualTo("hi there");
        assertThat(res.stopReason()).isEqualTo("end_turn");
        assertThat(res.usage()).isEqualTo(new Usage(12, 5, 0, 0));
        assertThat(res.model()).isEqualTo("gpt-4o-mini-2024-07-18");
    }

    @Test void completeSendsBearerAndOpenAiShape() {
        stubFor(post(urlEqualTo("/chat/completions")).willReturn(okJson("""
                {"id":"x","model":"gpt-4o","choices":[{"index":0,"finish_reason":"stop",
                  "message":{"role":"assistant","content":"ok"}}],
                 "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                """)));

        var req = new ProviderRequest("gpt-4o", 100, null, "be brief",
                List.of(Map.of("role", "user", "content", "hi")),
                null, null, null);
        provider.complete(req);

        verify(postRequestedFor(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer test-key"))
                .withRequestBody(containing("\"model\":\"gpt-4o\""))
                .withRequestBody(containing("\"max_completion_tokens\":100"))
                .withRequestBody(containing("\"role\":\"system\""))
                .withRequestBody(containing("\"content\":\"be brief\"")));
    }

    @Test void cachedTokensSubtractedFromInput() {
        stubFor(post(urlEqualTo("/chat/completions")).willReturn(okJson("""
                {"id":"x","model":"gpt-4o","choices":[{"index":0,"finish_reason":"stop",
                  "message":{"role":"assistant","content":"ok"}}],
                 "usage":{"prompt_tokens":100,"completion_tokens":5,"total_tokens":105,
                          "prompt_tokens_details":{"cached_tokens":40}}}
                """)));

        var req = new ProviderRequest("gpt-4o", 10, null, null,
                List.of(Map.of("role", "user", "content", "hi")), null, null, null);
        var res = provider.complete(req);

        // input was 100, of which 40 cached → 60 fresh + 40 cache_read
        assertThat(res.usage()).isEqualTo(new Usage(60, 5, 0, 40));
    }

    @Test void toolCallsBecomeAnthropicShapedContentBlocks() {
        stubFor(post(urlEqualTo("/chat/completions")).willReturn(okJson("""
                {"id":"x","model":"gpt-4o","choices":[{
                  "index":0,
                  "finish_reason":"tool_calls",
                  "message":{
                    "role":"assistant",
                    "content":null,
                    "tool_calls":[{
                      "id":"call_abc",
                      "type":"function",
                      "function":{"name":"cell.read","arguments":"{\\"id\\":\\"c1\\"}"}
                    }]
                  }}],
                 "usage":{"prompt_tokens":10,"completion_tokens":4,"total_tokens":14}}
                """)));

        var tools = List.<Map<String, Object>>of(Map.of(
                "name", "cell.read",
                "description", "read cell",
                "input_schema", Map.of("type", "object",
                        "properties", Map.of("id", Map.of("type", "string")),
                        "required", List.of("id"))));
        var req = new ProviderRequest("gpt-4o", 256, null, "sys",
                List.of(Map.of("role", "user", "content", "find c1")),
                tools, null, null);
        var res = provider.complete(req);

        assertThat(res.stopReason()).isEqualTo("tool_use");
        assertThat(res.contentBlocks().isArray()).isTrue();
        assertThat(res.contentBlocks().size()).isEqualTo(1);
        var block = res.contentBlocks().get(0);
        assertThat(block.path("type").asText()).isEqualTo("tool_use");
        assertThat(block.path("id").asText()).isEqualTo("call_abc");
        assertThat(block.path("name").asText()).isEqualTo("cell.read");
        assertThat(block.path("input").path("id").asText()).isEqualTo("c1");

        verify(postRequestedFor(urlEqualTo("/chat/completions"))
                .withRequestBody(containing("\"type\":\"function\""))
                .withRequestBody(containing("\"name\":\"cell.read\""))
                .withRequestBody(containing("\"parameters\":")));
    }

    @Test void visionSendsImageUrlDataBlock() {
        stubFor(post(urlEqualTo("/chat/completions")).willReturn(okJson("""
                {"id":"x","model":"gpt-4o","choices":[{"index":0,"finish_reason":"stop",
                  "message":{"role":"assistant","content":"a chart"}}],
                 "usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}
                """)));

        var res = provider.vision("gpt-4o", 256, "image/png", "AAAA", "describe");

        assertThat(res.text()).isEqualTo("a chart");
        verify(postRequestedFor(urlEqualTo("/chat/completions"))
                .withRequestBody(containing("\"type\":\"image_url\""))
                .withRequestBody(containing("\"url\":\"data:image/png;base64,AAAA\""))
                .withRequestBody(containing("\"text\":\"describe\"")));
    }

    @Test void visionMultiSendsImageUrlBlockPerImage() {
        stubFor(post(urlEqualTo("/chat/completions")).willReturn(okJson("""
                {"id":"x","model":"gpt-4o","choices":[{"index":0,"finish_reason":"stop",
                  "message":{"role":"assistant","content":"two charts"}}],
                 "usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}
                """)));

        var images = List.of(
                new LlmProvider.ImageInput("image/png", "AAAA"),
                new LlmProvider.ImageInput("image/jpeg", "BBBB"));
        var res = provider.visionMulti("gpt-4o", 256, images, "describe both");

        assertThat(res.text()).isEqualTo("two charts");
        verify(postRequestedFor(urlEqualTo("/chat/completions"))
                .withRequestBody(containing("\"type\":\"image_url\""))
                .withRequestBody(containing("\"url\":\"data:image/png;base64,AAAA\""))
                .withRequestBody(containing("\"url\":\"data:image/jpeg;base64,BBBB\""))
                .withRequestBody(containing("\"text\":\"describe both\"")));
    }

    @Test void errorResponseMapsToProviderException() {
        stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse()
                .withStatus(429)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"error":{"message":"rate limit","type":"rate_limit_error",
                                  "code":"rate_limit_exceeded"}}
                        """)));

        var req = new ProviderRequest("gpt-4o-mini", 10, null, null,
                List.of(Map.of("role", "user", "content", "hi")), null, null, null);
        try {
            provider.complete(req);
            org.junit.jupiter.api.Assertions.fail("expected ProviderException");
        } catch (LlmProvider.ProviderException e) {
            assertThat(e.statusCode()).isEqualTo(429);
            assertThat(e.errorCode()).isEqualTo("rate_limit_exceeded");
        }
    }

    @Test void errorWithOnlyTypeFallsBackToType() {
        stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse()
                .withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"error":{"message":"bad","type":"invalid_request_error"}}
                        """)));

        var req = new ProviderRequest("gpt-4o-mini", 10, null, null,
                List.of(Map.of("role", "user", "content", "hi")), null, null, null);
        try {
            provider.complete(req);
            org.junit.jupiter.api.Assertions.fail("expected ProviderException");
        } catch (LlmProvider.ProviderException e) {
            assertThat(e.statusCode()).isEqualTo(400);
            assertThat(e.errorCode()).isEqualTo("invalid_request_error");
        }
    }

    @Test void nameIsConstructorArgument() {
        var http = RestClient.builder()
                .baseUrl("http://localhost:" + wm.port())
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
        var xai = new OpenAiCompatibleProvider("xai", http, "xai-key");
        assertThat(xai.name()).isEqualTo("xai");
        assertThat(provider.name()).isEqualTo("openai");
    }

    @Test void finishReasonLengthMapsToMaxTokens() {
        stubFor(post(urlEqualTo("/chat/completions")).willReturn(okJson("""
                {"id":"x","model":"gpt-4o","choices":[{"index":0,"finish_reason":"length",
                  "message":{"role":"assistant","content":"truncated..."}}],
                 "usage":{"prompt_tokens":1,"completion_tokens":256,"total_tokens":257}}
                """)));
        var req = new ProviderRequest("gpt-4o", 256, null, null,
                List.of(Map.of("role", "user", "content", "long")), null, null, null);
        assertThat(provider.complete(req).stopReason()).isEqualTo("max_tokens");
    }
}
