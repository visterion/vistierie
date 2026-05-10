package de.vesterion.vistierie.provider;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class BedrockProviderConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BedrockProviderConfig.class);

    @Test
    void registersBedrockProviderWhenEnabled() {
        runner.withPropertyValues(
                "vistierie.bedrock.enabled=true",
                "vistierie.bedrock.region=us-east-1"
        ).run(ctx -> {
            assertThat(ctx).hasSingleBean(BedrockProvider.class);
            assertThat(ctx.getBean(BedrockProvider.class).name()).isEqualTo("bedrock");
        });
    }

    @Test
    void noBedrockProviderWhenDisabled() {
        runner.withPropertyValues("vistierie.bedrock.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(BedrockProvider.class));
    }

    @Test
    void noBedrockProviderByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(BedrockProvider.class));
    }
}
