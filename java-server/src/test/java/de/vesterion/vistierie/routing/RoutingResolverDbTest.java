package de.vesterion.vistierie.routing;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingResolverDbTest extends PostgresTestBase {

    @Autowired RoutingResolver resolver;
    @Autowired RoutingRuleRepository rules;
    @Autowired TenantRepository tenants;

    UUID tenantId;
    String tenantName;

    @BeforeEach
    void seed() {
        tenantId = UUID.randomUUID();
        tenantName = "tnt-" + tenantId.toString().substring(0, 8);
        tenants.insert(tenantId, tenantName, "x");
        resolver.bumpVersion();
    }

    private void rule(String realm, String purpose, String provider, String model,
                      int priority, boolean allowOverride, boolean locked) {
        var now = Instant.now();
        rules.insert(new RoutingRule(UUID.randomUUID(), tenantId, realm, purpose,
                provider, model, priority, allowOverride, locked, now, now));
        resolver.bumpVersion();
    }

    @Test
    void wildcardDefaultMatches() {
        rule(null, null, "anthropic", "claude-sonnet-4-6", 1000, false, false);
        var d = resolver.resolve(tenantName, null, "anything", null);
        assertThat(d.model()).isEqualTo("claude-sonnet-4-6");
    }

    @Test
    void purposeMatchBeatsWildcard() {
        rule(null, null,        "anthropic", "claude-sonnet-4-6", 1000, false, false);
        rule(null, "summarize", "anthropic", "claude-haiku-4-5",  500,  false, false);
        var d = resolver.resolve(tenantName, null, "summarize", null);
        assertThat(d.model()).isEqualTo("claude-haiku-4-5");
    }

    @Test
    void realmMatchBeatsPurposeMatchAtSamePriority() {
        rule(null, "summarize", "anthropic", "claude-haiku-4-5",  500, false, false);
        rule("medical", null,   "ollama",    "llama-3.1-70b",     500, false, false);
        var d = resolver.resolve(tenantName, "medical", "summarize", null);
        assertThat(d.provider()).isEqualTo("ollama");
    }

    @Test
    void realmPlusPurposeBeatsRealmOnlyAtSamePriority() {
        rule("medical", null,        "ollama", "llama-3.1-70b", 500, false, false);
        rule("medical", "summarize", "ollama", "llama-3.1-8b",  500, false, false);
        var d = resolver.resolve(tenantName, "medical", "summarize", null);
        assertThat(d.model()).isEqualTo("llama-3.1-8b");
    }

    @Test
    void lowerPriorityBeatsHigherSpecificity() {
        rule(null, null,             "anthropic", "claude-sonnet-4-6", 10,   false, false);
        rule("medical", "summarize", "ollama",    "llama-3.1-8b",      500,  false, false);
        var d = resolver.resolve(tenantName, "medical", "summarize", null);
        assertThat(d.model()).isEqualTo("claude-sonnet-4-6");
    }

    @Test
    void allowOverrideHonoredWhenNotLocked() {
        rule(null, "free_pick", "anthropic", "claude-sonnet-4-6", 500, true, false);
        var d = resolver.resolve(tenantName, null, "free_pick", "claude-opus-4-7");
        assertThat(d.model()).isEqualTo("claude-opus-4-7");
    }

    @Test
    void overrideIgnoredWhenLocked() {
        rule("medical", null, "ollama", "llama-3.1-70b", 10, true, true);
        var d = resolver.resolve(tenantName, "medical", "any", "claude-sonnet-4-6");
        assertThat(d.model()).isEqualTo("llama-3.1-70b");
        assertThat(d.allowOverride()).isFalse();
    }

    @Test
    void overrideIgnoredWhenAllowOverrideFalse() {
        rule(null, "summarize", "anthropic", "claude-haiku-4-5", 500, false, false);
        var d = resolver.resolve(tenantName, null, "summarize", "claude-opus-4-7");
        assertThat(d.model()).isEqualTo("claude-haiku-4-5");
    }

    @Test
    void noMatchingRuleThrows() {
        assertThatThrownBy(() -> resolver.resolve(tenantName, null, "any", null))
                .isInstanceOf(RoutingResolver.NoRouteException.class);
    }

    @Test
    void unknownTenantThrows() {
        assertThatThrownBy(() -> resolver.resolve("does-not-exist", null, "x", null))
                .isInstanceOf(RoutingResolver.NoRouteException.class);
    }
}
