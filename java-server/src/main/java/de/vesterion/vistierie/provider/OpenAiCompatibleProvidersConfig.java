package de.vesterion.vistierie.provider;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registers one OpenAiCompatibleProvider bean per non-empty entry under
 * vistierie.providers.<name>.* in application.yaml.
 *
 * Empty api-key entries are skipped silently — useful for local dev where
 * developers may have only some keys configured.
 *
 * Disabled when vistierie.mock-llm=true (mock mode handles all routing).
 */
@Configuration
@ConditionalOnProperty(value = "vistierie.mock-llm", havingValue = "false", matchIfMissing = true)
@Import(OpenAiCompatibleProvidersConfig.Registrar.class)
public class OpenAiCompatibleProvidersConfig {

    public record ProviderProperties(String baseUrl, String apiKey, Integer timeoutSeconds) {}

    public static class Registrar implements ImportBeanDefinitionRegistrar, EnvironmentAware {
        private Environment env;

        @Override public void setEnvironment(Environment environment) { this.env = environment; }

        @Override
        public void registerBeanDefinitions(AnnotationMetadata md, BeanDefinitionRegistry registry) {
            Map<String, ProviderProperties> providers = Binder.get(env)
                    .bind("vistierie.providers",
                            org.springframework.boot.context.properties.bind.Bindable
                                    .mapOf(String.class, ProviderProperties.class))
                    .orElse(new LinkedHashMap<>());

            for (var entry : providers.entrySet()) {
                String name = entry.getKey();
                ProviderProperties props = entry.getValue();
                if (props == null || props.apiKey() == null || props.apiKey().isBlank()) {
                    continue;
                }
                String baseUrl = props.baseUrl();
                if (baseUrl == null || baseUrl.isBlank()) {
                    throw new IllegalStateException(
                            "vistierie.providers." + name + ".base-url is required");
                }

                GenericBeanDefinition bd = new GenericBeanDefinition();
                bd.setBeanClass(OpenAiCompatibleProvider.class);
                bd.setInstanceSupplier(() -> {
                    var http = RestClient.builder()
                            .baseUrl(baseUrl)
                            .requestFactory(new SimpleClientHttpRequestFactory())
                            .build();
                    return new OpenAiCompatibleProvider(name, http, props.apiKey());
                });
                bd.setRole(BeanDefinition.ROLE_APPLICATION);
                registry.registerBeanDefinition("openAiCompatibleProvider_" + name, bd);
            }
        }
    }
}
