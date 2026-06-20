package de.vesterion.vistierie.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

import java.time.Duration;

@Configuration
@ConditionalOnProperty(value = "vistierie.bedrock.enabled", havingValue = "true")
public class BedrockProviderConfig {

    @Bean
    BedrockRuntimeClient bedrockRuntimeClient(
            @Value("${vistierie.bedrock.region:}") String region,
            @Value("${vistierie.bedrock.read-timeout-seconds:180}") int readTimeoutSeconds) {
        // Non-streaming converse() must receive the FULL response within the HTTP
        // client's socket (read) timeout. The SDK default (~30s) is too short for long
        // reasoning responses — e.g. Strigoi-Merger's heavy second turn over many EDGAR
        // filings — which produced "Read timed out (SDK Attempt Count: 4)" failures every
        // run. Use a generous, env-tunable read timeout.
        var http = Apache5HttpClient.builder()
                .connectionTimeout(Duration.ofSeconds(10))
                .socketTimeout(Duration.ofSeconds(readTimeoutSeconds));
        var builder = BedrockRuntimeClient.builder().httpClientBuilder(http);
        if (region != null && !region.isBlank()) {
            builder.region(Region.of(region));
        }
        // AWS_BEARER_TOKEN_BEDROCK env var is read natively by the SDK
        // (EnvironmentTokenSystemSettings) for ABSK API key auth.
        return builder.build();
    }

    @Bean
    BedrockProvider bedrockProvider(BedrockRuntimeClient client) {
        return new BedrockProvider(client);
    }
}
