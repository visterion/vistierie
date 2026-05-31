package de.vesterion.vistierie.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

@Configuration
@ConditionalOnProperty(value = "vistierie.bedrock.enabled", havingValue = "true")
public class BedrockProviderConfig {

    @Bean
    BedrockRuntimeClient bedrockRuntimeClient(
            @Value("${vistierie.bedrock.region:}") String region) {
        var builder = BedrockRuntimeClient.builder();
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
