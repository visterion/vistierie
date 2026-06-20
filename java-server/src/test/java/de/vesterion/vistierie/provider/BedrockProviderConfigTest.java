package de.vesterion.vistierie.provider;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring guard for the Bedrock client's custom read timeout (fix for the recurring
 * "Read timed out (SDK Attempt Count: 4)" Strigoi-Merger failures). Verifies the
 * {@link software.amazon.awssdk.http.apache5.Apache5HttpClient} builder wiring compiles
 * and the client builds without throwing. (The applied socket-timeout value itself is
 * not introspectable from the built client.)
 */
class BedrockProviderConfigTest {

    @Test
    void buildsClientWithCustomReadTimeout() {
        var config = new BedrockProviderConfig();
        try (var client = config.bedrockRuntimeClient("us-east-1", 180)) {
            assertThat(client).isNotNull();
        }
    }
}
