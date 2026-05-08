package de.vesterion.vistierie.provider;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import de.vesterion.vistierie.pricing.Usage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class AnthropicProviderBatchTest {

    WireMockServer wm;
    AnthropicProvider provider;
    static final ObjectMapper M = new ObjectMapper();

    @BeforeEach void up() {
        wm = new WireMockServer(0); wm.start();
        WireMock.configureFor("localhost", wm.port());
        var http = RestClient.builder()
                .baseUrl("http://localhost:" + wm.port())
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
        provider = new AnthropicProvider(http, "test-key", 60);
    }
    @AfterEach void down() { wm.stop(); }

    @Test
    void submitBatchPostsToBatchesEndpoint() throws Exception {
        stubFor(post(urlEqualTo("/v1/messages/batches"))
                .willReturn(okJson("""
                        {"id":"msgbatch_abc","processing_status":"in_progress"}
                        """)));

        var req = new ProviderRequest("claude-haiku-4-5", 1024, null,
                "system", List.of(Map.of("role","user","content","hi")),
                List.of(), null, Map.of());
        var sub = provider.submitBatch(List.of(new BatchItem("R1", req), new BatchItem("R2", req)));

        assertThat(sub.anthropicBatchId()).isEqualTo("msgbatch_abc");
        assertThat(sub.itemCount()).isEqualTo(2);
        verify(postRequestedFor(urlEqualTo("/v1/messages/batches"))
                .withHeader("x-api-key", equalTo("test-key"))
                .withHeader("anthropic-version", equalTo("2023-06-01"))
                .withRequestBody(matchingJsonPath("$.requests[0].custom_id", equalTo("R1")))
                .withRequestBody(matchingJsonPath("$.requests[0].params.model", equalTo("claude-haiku-4-5")))
                .withRequestBody(matchingJsonPath("$.requests[1].custom_id", equalTo("R2"))));
    }

    @Test
    void getBatchParsesProcessingCounts() throws Exception {
        stubFor(get(urlEqualTo("/v1/messages/batches/msgbatch_abc"))
                .willReturn(okJson("""
                        {
                          "id":"msgbatch_abc","processing_status":"ended",
                          "request_counts":{"processing":0,"succeeded":2,"errored":0,"canceled":0,"expired":0},
                          "results_url":"http://localhost:%d/results.jsonl"
                        }
                        """.formatted(wm.port()))));

        var s = provider.getBatch("msgbatch_abc");
        assertThat(s.processingStatus()).isEqualTo("ended");
        assertThat(s.succeeded()).isEqualTo(2);
        assertThat(s.resultsUrl()).contains("/results.jsonl");
    }

    @Test
    void streamResultsParsesJsonl() throws Exception {
        var jsonl = """
                {"custom_id":"R1","result":{"type":"succeeded","message":{"id":"m1","model":"claude-haiku-4-5","content":[{"type":"text","text":"hello"}],"stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":3}}}}
                {"custom_id":"R2","result":{"type":"errored","error":{"type":"invalid_request_error","message":"bad"}}}
                """.trim();
        stubFor(get(urlEqualTo("/results.jsonl"))
                .willReturn(aResponse().withHeader("Content-Type","application/x-ndjson").withBody(jsonl)));

        try (var stream = provider.streamResults("http://localhost:" + wm.port() + "/results.jsonl")) {
            var results = stream.collect(Collectors.toList());
            assertThat(results).hasSize(2);

            var r1 = results.get(0);
            assertThat(r1.customId()).isEqualTo("R1");
            assertThat(r1.type()).isEqualTo("succeeded");
            assertThat(r1.text()).isEqualTo("hello");
            assertThat(r1.usage()).isEqualTo(new Usage(5, 3, 0, 0));

            var r2 = results.get(1);
            assertThat(r2.type()).isEqualTo("errored");
            assertThat(r2.errorMessage()).contains("bad");
        }
    }
}
