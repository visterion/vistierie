package de.vesterion.vistierie.routing;

import de.vesterion.vistierie.tenants.TenantRepository;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class RoutingResolver {

    private final TenantRepository tenants;
    private final RoutingRuleRepository rules;

    private final AtomicLong version = new AtomicLong(0);
    private final ConcurrentHashMap<UUID, CacheEntry> cache = new ConcurrentHashMap<>();

    public RoutingResolver(TenantRepository tenants, RoutingRuleRepository rules) {
        this.tenants = tenants;
        this.rules = rules;
    }

    public void bumpVersion() {
        version.incrementAndGet();
        cache.clear();
    }

    public RoutingDecision resolve(String tenantName, String realm,
                                   String purpose, String requestedModel) {
        var tenant = tenants.findByName(tenantName)
                .orElseThrow(() -> new NoRouteException("unknown tenant " + tenantName));

        var entry = cache.get(tenant.id());
        long current = version.get();
        if (entry == null || entry.version() < current) {
            entry = new CacheEntry(current, rules.findByTenant(tenant.id()));
            cache.put(tenant.id(), entry);
        }

        var match = entry.rules().stream()
                .filter(r -> r.matches(realm, purpose))
                .min(Comparator
                        .comparingInt(RoutingRule::priority)
                        .thenComparing(Comparator.comparingInt(RoutingRule::specificity).reversed())
                        .thenComparing(r -> r.id().toString()))
                .orElseThrow(() -> new NoRouteException(
                        "no rule for tenant=" + tenantName +
                        " realm=" + realm + " purpose=" + purpose));

        var effectiveOverride = match.effectiveAllowOverride();
        var model = (requestedModel != null && effectiveOverride)
                ? requestedModel
                : match.model();

        return new RoutingDecision(match.provider(), model, effectiveOverride);
    }

    private record CacheEntry(long version, List<RoutingRule> rules) {}

    public static class NoRouteException extends RuntimeException {
        public NoRouteException(String m) { super(m); }
    }
}
