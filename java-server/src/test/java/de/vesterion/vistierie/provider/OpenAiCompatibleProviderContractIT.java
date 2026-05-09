package de.vesterion.vistierie.provider;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test against the official OpenAI OpenAPI spec via Prism.
 *
 * Prism (https://github.com/stoplightio/prism) parses an OpenAPI document and
 * spins up a mock server that:
 *   1. validates incoming requests against the spec — wrong field name, wrong
 *      type, missing required field → 422 from Prism, test fails;
 *   2. returns dynamic responses generated from the spec's schemas — so we
 *      verify our parser handles the response shape OpenAI actually
 *      documents, not a hand-crafted fixture we invented.
 *
 * Spec source: github.com/openai/openai-openapi (snapshot at
 * src/test/resources/openapi/openai.yaml). Bump that file when OpenAI
 * publishes wire changes we want to verify against.
 *
 * The test is tagged "integration" and runs whenever Docker is reachable;
 * Testcontainers handles container lifecycle. No real OPENAI_API_KEY needed —
 * Prism does not validate auth tokens.
 */
@Tag("integration")
class OpenAiCompatibleProviderContractIT {

    @SuppressWarnings("resource")
    static GenericContainer<?> prism = new GenericContainer<>("stoplight/prism:5")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("openapi/openai.yaml"),
                    "/tmp/openai.yaml")
            // -m false avoids a prism:5 multiprocess bug (TypeError on isPrimary).
            // -d (dynamic) generates spec-faithful responses from the schema.
            .withCommand("mock", "-h", "0.0.0.0", "-m", "false", "-d", "/tmp/openai.yaml")
            .withExposedPorts(4010)
            .waitingFor(Wait.forLogMessage(".*Prism is listening.*", 1));

    static OpenAiCompatibleProvider provider;

    @BeforeAll static void up() {
        prism.start();
        var baseUrl = "http://" + prism.getHost() + ":" + prism.getMappedPort(4010);
        var http = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
        provider = new OpenAiCompatibleProvider("openai", http, "test-key");
    }

    @AfterAll static void down() { prism.stop(); }

    @Test void completeAgainstOfficialSpec() {
        var req = new ProviderRequest(
                "gpt-4o-mini", 64, 0.2, "be brief",
                List.of(Map.of("role", "user", "content", "say hi")),
                null, null, null);

        // The contract assertions: if our request body violates the OpenAI spec
        // Prism returns 422 (parse() throws). If the response shape our parser
        // expects isn't present in the spec, fields come back blank and we'd
        // fail here. Numeric values are random — we don't assert on them.
        var res = provider.complete(req);

        assertThat(res.usage()).isNotNull();
        assertThat(res.model()).isNotNull();
        assertThat(res.stopReason()).isNotNull();
        assertThat(res.contentBlocks()).isNotNull();
    }

    @Test void completeWithToolsAgainstOfficialSpec() {
        var tools = List.<Map<String, Object>>of(Map.of(
                "name", "lookup",
                "description", "look up a value",
                "input_schema", Map.of("type", "object",
                        "properties", Map.of("id", Map.of("type", "string")),
                        "required", List.of("id"))));
        var req = new ProviderRequest("gpt-4o-mini", 64, null, "sys",
                List.of(Map.of("role", "user", "content", "find c1")),
                tools, null, null);

        // The interesting check: our Anthropic→OpenAI tool translation must
        // produce a body that conforms to the spec's ChatCompletionTool schema.
        // If we got the shape wrong, Prism returns 422.
        var res = provider.complete(req);
        assertThat(res).isNotNull();
    }

    @Test void visionAgainstOfficialSpec() {
        // Validates the image_url / data URL content-block shape against the spec.
        var res = provider.vision("gpt-4o-mini", 64, "image/png", "AAAA", "describe");
        assertThat(res).isNotNull();
        assertThat(res.usage()).isNotNull();
    }
}
