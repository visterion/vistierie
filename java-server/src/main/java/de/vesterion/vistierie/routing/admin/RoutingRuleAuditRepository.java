package de.vesterion.vistierie.routing.admin;

import de.vesterion.vistierie.routing.RoutingRule;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@Repository
public class RoutingRuleAuditRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public RoutingRuleAuditRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void record(String action, UUID ruleId, UUID tenantId,
                       RoutingRule before, RoutingRule after, String setBy) {
        try {
            String beforeStr = before == null ? null : json.writeValueAsString(before);
            String afterStr  = after  == null ? null : json.writeValueAsString(after);
            jdbc.sql("""
                    INSERT INTO vistierie.routing_rules_audit
                      (rule_id, tenant_id, action, before_json, after_json, set_by)
                    VALUES (?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?)
                    """).params(ruleId, tenantId, action, beforeStr, afterStr, setBy).update();
        } catch (Exception e) {
            throw new RuntimeException("audit insert failed", e);
        }
    }
}
