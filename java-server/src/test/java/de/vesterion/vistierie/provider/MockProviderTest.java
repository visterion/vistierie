package de.vesterion.vistierie.provider;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "vistierie.mock-llm=true",
        "vistierie.admin.token-hash=",
        "vistierie.anthropic.api-key=ignored",
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:mock;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
class MockProviderTest {
    @Autowired ProviderRegistry registry;

    @Test void mockReplacesAnthropic() {
        var p = registry.get("anthropic");
        assertThat(p).isInstanceOf(MockProvider.class);
    }
}
