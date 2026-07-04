package de.vesterion.vistierie.routing;

import de.vesterion.vistierie.tenants.Tenant;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoutingResolverTest {

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final RoutingRuleRepository rules = mock(RoutingRuleRepository.class);
    private final RoutingResolver resolver = new RoutingResolver(tenants, rules);

    private final UUID tenantId = UUID.randomUUID();
    private final Tenant tenant = new Tenant(tenantId, "tn", "h", null, null, null,
            Instant.parse("2026-01-01T00:00:00Z"));
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @BeforeEach void wireTenant() {
        when(tenants.findByName("tn")).thenReturn(Optional.of(tenant));
    }

    private RoutingRule rule(String realm, String purpose, String model, int prio,
                             boolean allow, boolean locked) {
        return new RoutingRule(UUID.randomUUID(), tenantId, realm, purpose,
                "anthropic", model, prio, allow, locked, now, now);
    }

    @Test void unknownTenantThrowsNoRoute() {
        when(tenants.findByName("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> resolver.resolve("ghost", "r", "p", null))
                .isInstanceOf(RoutingResolver.NoRouteException.class)
                .hasMessageContaining("unknown tenant");
    }

    @Test void noMatchingRuleThrowsNoRoute() {
        when(rules.findByTenant(tenantId)).thenReturn(List.of(
                rule("other", null, "m", 100, false, false)));
        assertThatThrownBy(() -> resolver.resolve("tn", "different", "p", null))
                .isInstanceOf(RoutingResolver.NoRouteException.class);
    }

    @Test void picksRuleWithLowestPriorityValue() {
        when(rules.findByTenant(tenantId)).thenReturn(List.of(
                rule(null, null, "default-m", 1000, false, false),
                rule("r", "p", "specific-m", 100, false, false)));
        var d = resolver.resolve("tn", "r", "p", null);
        assertThat(d.model()).isEqualTo("specific-m");
    }

    @Test void tieBreaksOnSpecificityWhenPriorityEqual() {
        var realmOnly  = rule("r", null, "realm-only", 100, false, false);
        var realmPurpose = rule("r", "p", "realm-purpose", 100, false, false);
        when(rules.findByTenant(tenantId)).thenReturn(List.of(realmOnly, realmPurpose));
        var d = resolver.resolve("tn", "r", "p", null);
        assertThat(d.model()).isEqualTo("realm-purpose");
    }

    @Test void wildcardMatchesAnyRealmOrPurpose() {
        when(rules.findByTenant(tenantId)).thenReturn(List.of(
                rule(null, null, "wild", 1000, false, false)));
        assertThat(resolver.resolve("tn", "anything", "thing", null).model()).isEqualTo("wild");
        assertThat(resolver.resolve("tn", null, null, null).model()).isEqualTo("wild");
    }

    @Test void overrideAllowsRequestedModelWhenAllowed() {
        when(rules.findByTenant(tenantId)).thenReturn(List.of(
                rule("r", "p", "default-m", 100, true, false)));
        var d = resolver.resolve("tn", "r", "p", "user-pick");
        assertThat(d.model()).isEqualTo("user-pick");
        assertThat(d.allowOverride()).isTrue();
    }

    @Test void lockedDefeatsAllowOverride() {
        when(rules.findByTenant(tenantId)).thenReturn(List.of(
                rule("r", "p", "default-m", 100, true, true)));
        var d = resolver.resolve("tn", "r", "p", "user-pick");
        assertThat(d.model()).isEqualTo("default-m");
        assertThat(d.allowOverride()).isFalse();
    }

    @Test void overrideIgnoredWhenAllowOverrideFalse() {
        when(rules.findByTenant(tenantId)).thenReturn(List.of(
                rule("r", "p", "default-m", 100, false, false)));
        var d = resolver.resolve("tn", "r", "p", "user-pick");
        assertThat(d.model()).isEqualTo("default-m");
    }

    @Test void cachesRulesUntilBumpVersion() {
        when(rules.findByTenant(tenantId)).thenReturn(List.of(
                rule("r", "p", "v1", 100, false, false)));
        resolver.resolve("tn", "r", "p", null);
        resolver.resolve("tn", "r", "p", null);
        resolver.resolve("tn", "r", "p", null);
        verify(rules, times(1)).findByTenant(tenantId);

        when(rules.findByTenant(tenantId)).thenReturn(List.of(
                rule("r", "p", "v2", 100, false, false)));
        resolver.bumpVersion();
        var d = resolver.resolve("tn", "r", "p", null);
        assertThat(d.model()).isEqualTo("v2");
        verify(rules, atLeastOnce()).findByTenant(tenantId);
    }

    @Test void resolvePassesFallbackThrough() {
        var ruleWithFallback = new RoutingRule(UUID.randomUUID(), tenantId, "r", "p",
                "anthropic", "claude-3-5-sonnet", "anthropic", "claude-haiku-4-5",
                100, false, false, now, now);
        when(rules.findByTenant(tenantId)).thenReturn(List.of(ruleWithFallback));
        var d = resolver.resolve("tn", "r", "p", null);
        assertThat(d.fallbackProvider()).isEqualTo("anthropic");
        assertThat(d.fallbackModel()).isEqualTo("claude-haiku-4-5");
    }

    @Test void modelOverrideAlsoAppliesToFallbackModel() {
        var ruleWithFallback = new RoutingRule(UUID.randomUUID(), tenantId, "r", "p",
                "anthropic", "claude-3-5-sonnet", "anthropic", "claude-haiku-4-5",
                100, true, false, now, now);
        when(rules.findByTenant(tenantId)).thenReturn(List.of(ruleWithFallback));
        var d = resolver.resolve("tn", "r", "p", "claude-opus-4-8");
        assertThat(d.model()).isEqualTo("claude-opus-4-8");
        // when the consumer overrides the model, the fallback keeps that model
        // (same model, different provider) instead of the rule's fallback_model:
        assertThat(d.fallbackModel()).isEqualTo("claude-opus-4-8");
    }

    @Test void ruleWithoutFallbackYieldsNulls() {
        when(rules.findByTenant(tenantId)).thenReturn(List.of(
                rule("r", "p", "default-m", 100, false, false)));
        var d = resolver.resolve("tn", "r", "p", null);
        assertThat(d.fallbackProvider()).isNull();
        assertThat(d.fallbackModel()).isNull();
    }
}
