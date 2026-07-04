package de.vesterion.vistierie.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/** Registers the subscription provider only when explicitly enabled and not in mock mode. */
@Configuration
@ConditionalOnExpression("${vistierie.claude-subscription.enabled:false} and !${vistierie.mock-llm:false}")
public class ClaudeSubscriptionProviderConfig {

    @Bean
    public ClaudeSubscriptionProvider claudeSubscriptionProvider(
            @Value("${vistierie.claude-subscription.base-url}") String baseUrl,
            @Value("${vistierie.claude-subscription.timeout-seconds:300}") int timeoutSeconds) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        var http = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
        return new ClaudeSubscriptionProvider(http);
    }
}
