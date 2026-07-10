package de.vesterion.vistierie.routing.admin;

import de.vesterion.vistierie.provider.ProviderRegistry;
import de.vesterion.vistierie.routing.RoutingResolver;
import de.vesterion.vistierie.routing.RoutingRule;
import de.vesterion.vistierie.routing.RoutingRuleRepository;
import de.vesterion.vistierie.tenants.Tenant;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminRoutingRuleServiceTest {

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final RoutingRuleRepository rules = mock(RoutingRuleRepository.class);
    private final RoutingRuleAuditRepository audit = mock(RoutingRuleAuditRepository.class);
    private final RoutingResolver resolver = mock(RoutingResolver.class);
    private final ProviderRegistry providers = mock(ProviderRegistry.class);

    private final AdminRoutingRuleService svc =
            new AdminRoutingRuleService(tenants, rules, audit, resolver, providers);

    private final UUID tenantId = UUID.randomUUID();
    private final Tenant tenant = new Tenant(tenantId, "tn", "h", null, null, null,
            Instant.parse("2026-01-01T00:00:00Z"));

    @BeforeEach void wireDefaults() {
        when(tenants.findByName("tn")).thenReturn(Optional.of(tenant));
        when(providers.has("anthropic")).thenReturn(true);
    }

    @Test void createWithDefaultPriorityInsertsAndAudits() {
        svc.create("tn", "myrealm", "summarize", "anthropic", "claude-haiku-4-5",
                null, null, null, false, false);

        var captor = ArgumentCaptor.forClass(RoutingRule.class);
        verify(rules).insert(captor.capture());
        var inserted = captor.getValue();
        assertThat(inserted.priority()).isEqualTo(100);
        assertThat(inserted.tenantId()).isEqualTo(tenantId);
        assertThat(inserted.realm()).isEqualTo("myrealm");

        verify(audit).record(eq("create"), eq(inserted.id()), eq(tenantId),
                eq(null), eq(inserted), eq("admin"));
        verify(resolver).bumpVersion();
    }

    @Test void createUnknownTenant() {
        when(tenants.findByName("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.create("ghost", "r", "p", "anthropic", "m",
                null, null, null, false, false))
                .isInstanceOf(AdminRoutingRuleService.BadInputException.class)
                .hasMessageContaining("unknown tenant");
        verify(rules, never()).insert(any());
    }

    @Test void createUnknownProvider() {
        when(providers.has("unknownprov")).thenReturn(false);
        assertThatThrownBy(() -> svc.create("tn", "r", "p", "unknownprov", "m",
                null, null, null, false, false))
                .isInstanceOf(AdminRoutingRuleService.BadInputException.class)
                .hasMessageContaining("unknown provider");
    }

    @Test void createPriorityOutOfRange() {
        assertThatThrownBy(() -> svc.create("tn", "r", "p", "anthropic", "m",
                null, null, -1, false, false))
                .isInstanceOf(AdminRoutingRuleService.BadInputException.class);
        assertThatThrownBy(() -> svc.create("tn", "r", "p", "anthropic", "m",
                null, null, 10001, false, false))
                .isInstanceOf(AdminRoutingRuleService.BadInputException.class);
    }

    @Test void createWildcardConflictsIfWildcardExists() {
        when(rules.existsWildcard(tenantId)).thenReturn(true);
        assertThatThrownBy(() -> svc.create("tn", null, null, "anthropic", "m",
                null, null, null, false, false))
                .isInstanceOf(AdminRoutingRuleService.ConflictException.class)
                .hasMessageContaining("wildcard");
    }

    @Test void createTranslatesDuplicateKeyToConflict() {
        org.mockito.Mockito.doThrow(new DuplicateKeyException("dup"))
                .when(rules).insert(any());
        assertThatThrownBy(() -> svc.create("tn", "r", "p", "anthropic", "m",
                null, null, null, false, false))
                .isInstanceOf(AdminRoutingRuleService.ConflictException.class)
                .hasMessageContaining("duplicate");
        verify(audit, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test void createWithFallbackStoresIt() {
        when(providers.has("claude-subscription")).thenReturn(true);
        when(providers.has("anthropic")).thenReturn(true);
        var r = svc.create("tn", "r", "p", "claude-subscription", "claude-opus-4-8",
                "anthropic", "claude-opus-4-8", 50, false, false);
        assertThat(r.fallbackProvider()).isEqualTo("anthropic");
    }

    @Test void createRejectsHalfSetFallback() {
        when(providers.has("anthropic")).thenReturn(true);
        assertThatThrownBy(() -> svc.create("tn", "r", "p", "anthropic", "m",
                "claude-subscription", null, null, false, false))
                .isInstanceOf(AdminRoutingRuleService.BadInputException.class)
                .hasMessageContaining("together");
    }

    @Test void createRejectsUnknownFallbackProvider() {
        when(providers.has("anthropic")).thenReturn(true);
        when(providers.has("nope")).thenReturn(false);
        assertThatThrownBy(() -> svc.create("tn", "r", "p", "anthropic", "m",
                "nope", "m2", null, false, false))
                .isInstanceOf(AdminRoutingRuleService.BadInputException.class);
    }

    @Test void createRejectsFallbackEqualToPrimary() {
        when(providers.has("anthropic")).thenReturn(true);
        assertThatThrownBy(() -> svc.create("tn", "r", "p", "anthropic", "m",
                "anthropic", "m", null, false, false))
                .isInstanceOf(AdminRoutingRuleService.BadInputException.class)
                .hasMessageContaining("differ");
    }

    @Test void getThrowsBadInputWhenMissing() {
        var id = UUID.randomUUID();
        when(rules.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.get(id))
                .isInstanceOf(AdminRoutingRuleService.BadInputException.class);
    }

    @Test void patchUpdatesProvidedFieldsAndPreservesOthers() {
        var id = UUID.randomUUID();
        var now = Instant.parse("2026-01-01T00:00:00Z");
        var before = new RoutingRule(id, tenantId, "r", "p",
                "anthropic", "claude-haiku-4-5", 100, false, false, now, now);
        var after = new RoutingRule(id, tenantId, "r", "p",
                "anthropic", "claude-sonnet-4-6", 100, false, false, now, now);
        when(rules.findById(id)).thenReturn(Optional.of(before), Optional.of(after));

        svc.patch(id, null, "claude-sonnet-4-6", null, null, null, null, null, null);

        verify(rules).update(eq(id), eq("anthropic"), eq("claude-sonnet-4-6"),
                eq(null), eq(null), eq(null), eq(100), eq(false), eq(false));
        verify(audit).record(eq("update"), eq(id), eq(tenantId),
                eq(before), eq(after), eq("admin"));
        verify(resolver).bumpVersion();
    }

    @Test void patchUnknownProvider() {
        var id = UUID.randomUUID();
        var now = Instant.now();
        var before = new RoutingRule(id, tenantId, "r", "p",
                "anthropic", "m", 100, false, false, now, now);
        when(rules.findById(id)).thenReturn(Optional.of(before));
        when(providers.has("nope")).thenReturn(false);

        assertThatThrownBy(() -> svc.patch(id, "nope", null, null, null, null, null, null, null))
                .isInstanceOf(AdminRoutingRuleService.BadInputException.class)
                .hasMessageContaining("unknown provider");
        verify(rules, never()).update(any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean(), anyBoolean());
    }

    @Test void patchPriorityOutOfRange() {
        var id = UUID.randomUUID();
        var now = Instant.now();
        var before = new RoutingRule(id, tenantId, "r", "p",
                "anthropic", "m", 100, false, false, now, now);
        when(rules.findById(id)).thenReturn(Optional.of(before));

        assertThatThrownBy(() -> svc.patch(id, null, null, null, null, null, 99999, null, null))
                .isInstanceOf(AdminRoutingRuleService.BadInputException.class);
    }

    @Test void patchClearFallbackRemovesIt() {
        var id = UUID.randomUUID();
        var now = Instant.parse("2026-01-01T00:00:00Z");
        var before = new RoutingRule(id, tenantId, "r", "p",
                "claude-subscription", "m", "anthropic", "m", 100, false, false, now, now);
        var after = new RoutingRule(id, tenantId, "r", "p",
                "claude-subscription", "m", null, null, 100, false, false, now, now);
        when(rules.findById(id)).thenReturn(Optional.of(before), Optional.of(after));
        when(providers.has("claude-subscription")).thenReturn(true);

        var patched = svc.patch(id, null, null, null, null, true, null, null, null);

        assertThat(patched.fallbackProvider()).isNull();
        assertThat(patched.fallbackModel()).isNull();
        verify(rules).update(eq(id), eq("claude-subscription"), eq("m"),
                eq(null), eq(null), eq(null), eq(100), eq(false), eq(false));
    }

    @Test void deleteRefusesWildcardDefault() {
        var id = UUID.randomUUID();
        var now = Instant.now();
        var wildcard = new RoutingRule(id, tenantId, null, null,
                "anthropic", "m", 1000, false, false, now, now);
        when(rules.findById(id)).thenReturn(Optional.of(wildcard));

        assertThatThrownBy(() -> svc.delete(id))
                .isInstanceOf(AdminRoutingRuleService.LastDefaultException.class);
        verify(rules, never()).delete(any());
    }

    @Test void deleteRemovesNonWildcardRule() {
        var id = UUID.randomUUID();
        var now = Instant.now();
        var rule = new RoutingRule(id, tenantId, "r", "p",
                "anthropic", "m", 100, false, false, now, now);
        when(rules.findById(id)).thenReturn(Optional.of(rule));

        svc.delete(id);

        verify(rules).delete(id);
        verify(audit).record(eq("delete"), eq(id), eq(tenantId),
                eq(rule), eq(null), eq("admin"));
        verify(resolver).bumpVersion();
    }

    @Test void listWithTenantFilterResolvesId() {
        when(rules.findAll(eq(tenantId), eq("r"), eq("p"))).thenReturn(java.util.List.of());
        svc.list("tn", "r", "p");
        verify(rules).findAll(eq(tenantId), eq("r"), eq("p"));
    }

    @Test void listUnknownTenant() {
        when(tenants.findByName("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.list("ghost", null, null))
                .isInstanceOf(AdminRoutingRuleService.BadInputException.class);
    }

    @Test void listWithoutTenantFilterPassesNull() {
        when(rules.findAll(eq(null), eq(null), eq(null))).thenReturn(java.util.List.of());
        svc.list(null, null, null);
        verify(rules).findAll(eq(null), eq(null), eq(null));
    }
}
