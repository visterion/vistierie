package de.vesterion.vistierie.routing;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RoutingRuleRepository {

    private final JdbcClient jdbc;

    public RoutingRuleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(RoutingRule r) {
        jdbc.sql("""
                INSERT INTO vistierie.routing_rules
                  (id, tenant_id, realm, purpose, provider, model,
                   priority, allow_override, locked, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(
                r.id(), r.tenantId(), r.realm(), r.purpose(),
                r.provider(), r.model(), r.priority(),
                r.allowOverride(), r.locked(),
                Timestamp.from(r.createdAt()), Timestamp.from(r.updatedAt())
        ).update();
    }

    public Optional<RoutingRule> findById(UUID id) {
        return jdbc.sql("""
                SELECT id, tenant_id, realm, purpose, provider, model,
                       priority, allow_override, locked, created_at, updated_at
                FROM vistierie.routing_rules WHERE id = ?
                """).param(id).query(this::map).optional();
    }

    public List<RoutingRule> findByTenant(UUID tenantId) {
        return jdbc.sql("""
                SELECT id, tenant_id, realm, purpose, provider, model,
                       priority, allow_override, locked, created_at, updated_at
                FROM vistierie.routing_rules
                WHERE tenant_id = ?
                ORDER BY priority ASC
                """).param(tenantId).query(this::map).list();
    }

    public List<RoutingRule> findAll(UUID tenantFilter, String realmFilter, String purposeFilter) {
        var sql = new StringBuilder("""
                SELECT id, tenant_id, realm, purpose, provider, model,
                       priority, allow_override, locked, created_at, updated_at
                FROM vistierie.routing_rules WHERE 1=1
                """);
        var params = new java.util.ArrayList<Object>();
        if (tenantFilter != null) { sql.append(" AND tenant_id = ?"); params.add(tenantFilter); }
        if (realmFilter   != null) { sql.append(" AND realm   = ?"); params.add(realmFilter); }
        if (purposeFilter != null) { sql.append(" AND purpose = ?"); params.add(purposeFilter); }
        sql.append(" ORDER BY tenant_id, priority");
        var spec = jdbc.sql(sql.toString());
        for (Object p : params) spec = spec.param(p);
        return spec.query(this::map).list();
    }

    public void update(UUID id, String provider, String model,
                       int priority, boolean allowOverride, boolean locked) {
        jdbc.sql("""
                UPDATE vistierie.routing_rules
                   SET provider = ?, model = ?, priority = ?,
                       allow_override = ?, locked = ?, updated_at = now()
                 WHERE id = ?
                """).params(provider, model, priority, allowOverride, locked, id).update();
    }

    public void delete(UUID id) {
        jdbc.sql("DELETE FROM vistierie.routing_rules WHERE id = ?").param(id).update();
    }

    public boolean existsWildcard(UUID tenantId) {
        return jdbc.sql("""
                SELECT 1 FROM vistierie.routing_rules
                 WHERE tenant_id = ? AND realm IS NULL AND purpose IS NULL
                 LIMIT 1
                """).param(tenantId).query(Integer.class).optional().isPresent();
    }

    public long countByTenant(UUID tenantId) {
        return jdbc.sql("SELECT COUNT(*) FROM vistierie.routing_rules WHERE tenant_id = ?")
                .param(tenantId).query(Long.class).single();
    }

    private RoutingRule map(java.sql.ResultSet rs, int rn) throws java.sql.SQLException {
        return new RoutingRule(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("tenant_id"),
                rs.getString("realm"),
                rs.getString("purpose"),
                rs.getString("provider"),
                rs.getString("model"),
                rs.getInt("priority"),
                rs.getBoolean("allow_override"),
                rs.getBoolean("locked"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }
}
