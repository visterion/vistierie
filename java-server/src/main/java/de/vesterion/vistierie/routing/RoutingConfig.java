package de.vesterion.vistierie.routing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "routing")
public class RoutingConfig {
    private Map<String, TenantRouting> tenants = new HashMap<>();
    public Map<String, TenantRouting> getTenants() { return tenants; }
    public void setTenants(Map<String, TenantRouting> v) { this.tenants = v; }

    public static class TenantRouting {
        private Rule defaultRule;
        private Map<String, Rule> purposes = new HashMap<>();
        public Rule getDefault() { return defaultRule; }
        public void setDefault(Rule v) { this.defaultRule = v; }
        public Map<String, Rule> getPurposes() { return purposes; }
        public void setPurposes(Map<String, Rule> v) { this.purposes = v; }
    }

    public static class Rule {
        private String provider;
        private String model;
        private boolean allowOverride;
        public String getProvider() { return provider; }
        public void setProvider(String v) { this.provider = v; }
        public String getModel() { return model; }
        public void setModel(String v) { this.model = v; }
        public boolean isAllowOverride() { return allowOverride; }
        public void setAllowOverride(boolean v) { this.allowOverride = v; }
    }
}
