package de.vesterion.vistierie.provider;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleProvidersConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OpenAiCompatibleProvidersConfig.class);

    @Test void registersBeanPerEntryWithKey() {
        runner.withPropertyValues(
                "vistierie.providers.openai.base-url=https://api.openai.com/v1",
                "vistierie.providers.openai.api-key=sk-test",
                "vistierie.providers.xai.base-url=https://api.x.ai/v1",
                "vistierie.providers.xai.api-key=xai-test",
                "vistierie.mock-llm=false"
        ).run(ctx -> {
            var beans = ctx.getBeansOfType(OpenAiCompatibleProvider.class);
            assertThat(beans).hasSize(2);
            var names = beans.values().stream().map(OpenAiCompatibleProvider::name).toList();
            assertThat(names).containsExactlyInAnyOrder("openai", "xai");
        });
    }

    @Test void skipsEntryWithEmptyKey() {
        runner.withPropertyValues(
                "vistierie.providers.openai.base-url=https://api.openai.com/v1",
                "vistierie.providers.openai.api-key=",
                "vistierie.providers.xai.base-url=https://api.x.ai/v1",
                "vistierie.providers.xai.api-key=xai-test",
                "vistierie.mock-llm=false"
        ).run(ctx -> {
            var beans = ctx.getBeansOfType(OpenAiCompatibleProvider.class);
            assertThat(beans).hasSize(1);
            assertThat(beans.values().iterator().next().name()).isEqualTo("xai");
        });
    }

    @Test void registersNothingWhenMockMode() {
        runner.withPropertyValues(
                "vistierie.providers.openai.base-url=https://api.openai.com/v1",
                "vistierie.providers.openai.api-key=sk-test",
                "vistierie.mock-llm=true"
        ).run(ctx -> {
            var beans = ctx.getBeansOfType(OpenAiCompatibleProvider.class);
            assertThat(beans).isEmpty();
        });
    }
}
