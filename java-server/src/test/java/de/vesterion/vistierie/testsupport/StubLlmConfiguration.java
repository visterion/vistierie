package de.vesterion.vistierie.testsupport;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("test-stub-llm")
public class StubLlmConfiguration {
    @Bean
    @ConditionalOnProperty(value = "vistierie.mock-llm", havingValue = "false", matchIfMissing = true)
    public StubLlmProvider stubLlmProvider() {
        return new StubLlmProvider();
    }
}
