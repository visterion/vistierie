package de.vesterion.vistierie.routing.admin;

import de.vesterion.vistierie.provider.ProviderRegistry;
import de.vesterion.vistierie.routing.RoutingResolver;
import de.vesterion.vistierie.routing.RoutingRule;
import de.vesterion.vistierie.routing.RoutingRuleRepository;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AdminRoutingRuleService {

    private final TenantRepository tenants;
    private final RoutingRuleRepository rules;
    private final RoutingRuleAuditRepository audit;
    private final RoutingResolver resolver;
    private final ProviderRegistry providers;

    public AdminRoutingRuleService(TenantRepository tenants,
                                   RoutingRuleRepository rules,
                                   RoutingRuleAuditRepository audit,
                                   RoutingResolver resolver,
                                   ProviderRegistry providers) {
        this.tenants = tenants;
        this.rules = rules;
        this.audit = audit;
        this.resolver = resolver;
        this.providers = providers;
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String m) { super(m); }
    }
    public static class BadInputException extends RuntimeException {
        public BadInputException(String m) { super(m); }
    }
    public static class LastDefaultException extends RuntimeException {
        public LastDefaultException(String m) { super(m); }
    }

    @Transactional
    public RoutingRule create(String tenantName, String realm, String purpose,
                              String provider, String model, Integer priority,
                              boolean allowOverride, boolean locked) {
        var tenant = tenants.findByName(tenantName)
                .orElseThrow(() -> new BadInputException("unknown tenant " + tenantName));
        if (!providers.has(provider)) {
            throw new BadInputException("unknown provider " + provider);
        }
        int p = priority == null ? 100 : priority;
        if (p < 0 || p > 10000) throw new BadInputException("priority out of range");

        if (realm == null && purpose == null && rules.existsWildcard(tenant.id())) {
            throw new ConflictException("wildcard rule already exists for tenant");
        }

        var now = Instant.now();
        var rule = new RoutingRule(UUID.randomUUID(), tenant.id(), realm, purpose,
                provider, model, p, allowOverride, locked, now, now);
        try {
            rules.insert(rule);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("duplicate (tenant, realm, purpose)");
        }
        audit.record("create", rule.id(), tenant.id(), null, rule, "admin");
        resolver.bumpVersion();
        return rule;
    }

    public List<RoutingRule> list(String tenantNameFilter, String realmFilter, String purposeFilter) {
        UUID tenantId = null;
        if (tenantNameFilter != null) {
            tenantId = tenants.findByName(tenantNameFilter)
                    .orElseThrow(() -> new BadInputException("unknown tenant " + tenantNameFilter))
                    .id();
        }
        return rules.findAll(tenantId, realmFilter, purposeFilter);
    }

    public RoutingRule get(UUID id) {
        return rules.findById(id)
                .orElseThrow(() -> new BadInputException("rule not found"));
    }

    @Transactional
    public RoutingRule patch(UUID id, String provider, String model, Integer priority,
                             Boolean allowOverride, Boolean locked) {
        var before = get(id);
        var newProvider = provider != null ? provider : before.provider();
        var newModel = model != null ? model : before.model();
        var newPriority = priority != null ? priority : before.priority();
        var newAllow = allowOverride != null ? allowOverride : before.allowOverride();
        var newLocked = locked != null ? locked : before.locked();

        if (provider != null && !providers.has(provider)) {
            throw new BadInputException("unknown provider " + provider);
        }
        if (newPriority < 0 || newPriority > 10000) {
            throw new BadInputException("priority out of range");
        }

        rules.update(id, newProvider, newModel, newPriority, newAllow, newLocked);
        var after = rules.findById(id).orElseThrow();
        audit.record("update", id, before.tenantId(), before, after, "admin");
        resolver.bumpVersion();
        return after;
    }

    @Transactional
    public void delete(UUID id) {
        var before = get(id);
        if (before.realm() == null && before.purpose() == null) {
            throw new LastDefaultException("cannot delete the tenant's wildcard default rule");
        }
        rules.delete(id);
        audit.record("delete", id, before.tenantId(), before, null, "admin");
        resolver.bumpVersion();
    }
}
