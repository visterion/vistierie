package de.vesterion.vistierie.routing;

import org.springframework.stereotype.Component;

@Component
public class RoutingResolver {

    private final RoutingConfig cfg;

    public RoutingResolver(RoutingConfig cfg) { this.cfg = cfg; }

    public RoutingDecision resolve(String tenant, String purpose, String overrideModel) {
        var t = cfg.getTenants().get(tenant);
        if (t == null) throw new NoRouteException("no routing for tenant " + tenant);
        var rule = t.getPurposes().getOrDefault(purpose, t.getDefault());
        if (rule == null) throw new NoRouteException("no rule for tenant " + tenant + " purpose " + purpose);
        var model = (overrideModel != null && rule.isAllowOverride()) ? overrideModel : rule.getModel();
        return new RoutingDecision(rule.getProvider(), model, rule.isAllowOverride());
    }

    public static class NoRouteException extends RuntimeException {
        public NoRouteException(String m) { super(m); }
    }
}
