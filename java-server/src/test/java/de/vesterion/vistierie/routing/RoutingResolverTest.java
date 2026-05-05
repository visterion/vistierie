package de.vesterion.vistierie.routing;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "vistierie.admin.token-hash=",
        "vistierie.anthropic.api-key=k",
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:rt;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
class RoutingResolverTest {
    @Autowired RoutingResolver resolver;

    @Test void purposeMatchWins() {
        var d = resolver.resolve("hivemem", "summarize_cell", null);
        assertThat(d.provider()).isEqualTo("anthropic");
        assertThat(d.model()).isEqualTo("claude-haiku-4-5");
        assertThat(d.allowOverride()).isFalse();
    }

    @Test void fallsBackToTenantDefault() {
        var d = resolver.resolve("hivemem", "unknown_purpose", null);
        assertThat(d.model()).isEqualTo("claude-sonnet-4-6");
    }

    @Test void rejectsUnknownTenant() {
        assertThatThrownBy(() -> resolver.resolve("bogus", "x", null))
                .isInstanceOf(RoutingResolver.NoRouteException.class);
    }

    @Test void overrideHonoredWhenAllowed() {
        var d = resolver.resolve("hivemem", "free_pick", "claude-opus-4-7");
        assertThat(d.model()).isEqualTo("claude-opus-4-7");
    }

    @Test void overrideIgnoredWhenForbidden() {
        var d = resolver.resolve("hivemem", "summarize_cell", "claude-opus-4-7");
        assertThat(d.model()).isEqualTo("claude-haiku-4-5");
    }
}
