package de.vesterion.vistierie.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

import java.util.Base64;

@Configuration
@ConditionalOnProperty(value = "vistierie.bedrock.enabled", havingValue = "true")
public class BedrockProviderConfig {

    @Bean
    BedrockRuntimeClient bedrockRuntimeClient(
            @Value("${vistierie.bedrock.region:}") String region,
            @Value("${vistierie.bedrock.api-key:}") String apiKey) {
        var builder = BedrockRuntimeClient.builder();
        if (region != null && !region.isBlank()) {
            builder.region(Region.of(region));
        }
        if (apiKey != null && !apiKey.isBlank()) {
            // Bedrock API Keys are ABSK<base64(accessKeyId:secretKey)>
            String decoded = new String(Base64.getDecoder().decode(apiKey.substring(4)));
            int colon = decoded.indexOf(':');
            String accessKeyId = decoded.substring(0, colon);
            String secretKey = decoded.substring(colon + 1);
            builder.credentialsProvider(
                    StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKeyId, secretKey)));
        }
        return builder.build();
    }

    @Bean
    BedrockProvider bedrockProvider(BedrockRuntimeClient client) {
        return new BedrockProvider(client);
    }
}
