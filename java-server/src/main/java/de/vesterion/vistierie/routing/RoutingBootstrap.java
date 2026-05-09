package de.vesterion.vistierie.routing;

import de.vesterion.vistierie.tenants.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class RoutingBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RoutingBootstrap.class);

    private final RoutingConfig yaml;
    private final RoutingRuleRepository rules;
    private final TenantRepository tenants;

    public RoutingBootstrap(RoutingConfig yaml,
                            RoutingRuleRepository rules,
                            TenantRepository tenants) {
        this.yaml = yaml;
        this.rules = rules;
        this.tenants = tenants;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (yaml == null || yaml.getTenants() == null) return;
        for (var entry : yaml.getTenants().entrySet()) {
            var tenantName = entry.getKey();
            var t = tenants.findByName(tenantName).orElse(null);
            if (t == null) {
                log.info("RoutingBootstrap: skipping unknown tenant '{}' from YAML", tenantName);
                continue;
            }
            if (rules.countByTenant(t.id()) > 0) {
                log.info("RoutingBootstrap: tenant '{}' already has rules, skipping", tenantName);
                continue;
            }
            seedFromYaml(t.id(), entry.getValue());
        }
    }

    private void seedFromYaml(UUID tenantId, RoutingConfig.TenantRouting tenantYaml) {
        var now = Instant.now();
        if (tenantYaml.getDefault() != null) {
            var d = tenantYaml.getDefault();
            rules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, null,
                    d.getProvider(), d.getModel(), 1000,
                    d.isAllowOverride(), false, now, now));
        }
        if (tenantYaml.getPurposes() != null) {
            for (var pe : tenantYaml.getPurposes().entrySet()) {
                var purpose = pe.getKey();
                var rule = pe.getValue();
                rules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, purpose,
                        rule.getProvider(), rule.getModel(), 500,
                        rule.isAllowOverride(), false, now, now));
            }
        }
        log.info("RoutingBootstrap: seeded {} rules for tenant_id={}",
                rules.countByTenant(tenantId), tenantId);
    }
}
