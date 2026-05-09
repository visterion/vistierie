package de.vesterion.vistierie.audit.admin;

import de.vesterion.vistierie.audit.admin.dto.AdminLlmCallRow;
import de.vesterion.vistierie.audit.admin.dto.AdminRunSummary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Repository
public class AdminAuditRepository {

    private final JdbcClient jdbc;

    public AdminAuditRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<AdminRunSummary> findRuns(String tenant, String agent, List<String> statuses,
                                          Instant from, Instant to, int limit, int offset) {
        var sql = new StringBuilder("""
                SELECT r.id, t.name AS tenant, a.name AS agent, r.trigger, r.status,
                       r.started_at, r.finished_at,
                       CASE WHEN r.finished_at IS NOT NULL
                            THEN (EXTRACT(EPOCH FROM (r.finished_at - r.started_at)) * 1000)::bigint
                            ELSE NULL END AS duration_ms,
                       (SELECT COUNT(*) FROM vistierie.llm_calls c WHERE c.run_id = r.id) AS llm_calls_count,
                       COALESCE((SELECT SUM(cost_micros) FROM vistierie.llm_calls c WHERE c.run_id = r.id), 0) AS total_cost_micros
                  FROM vistierie.runs r
                  JOIN vistierie.tenants t ON t.id = r.tenant_id
                  JOIN vistierie.agents a ON a.id = r.agent_id
                 WHERE 1=1
                """);
        var params = new ArrayList<Object>();
        if (tenant != null) { sql.append(" AND t.name = ?"); params.add(tenant); }
        if (agent != null)  { sql.append(" AND a.name = ?"); params.add(agent); }
        if (statuses != null && !statuses.isEmpty()) {
            sql.append(" AND r.status = ANY(?)");
            params.add(statuses.toArray(new String[0]));
        }
        if (from != null) { sql.append(" AND r.started_at >= ?"); params.add(java.sql.Timestamp.from(from)); }
        if (to != null)   { sql.append(" AND r.started_at <  ?"); params.add(java.sql.Timestamp.from(to)); }
        sql.append(" ORDER BY r.started_at DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        var spec = jdbc.sql(sql.toString());
        for (Object p : params) spec = spec.param(p);
        return spec.query(this::mapRunSummary).list();
    }

    public long countRuns(String tenant, String agent, List<String> statuses, Instant from, Instant to) {
        var sql = new StringBuilder("""
                SELECT COUNT(*)
                  FROM vistierie.runs r
                  JOIN vistierie.tenants t ON t.id = r.tenant_id
                  JOIN vistierie.agents a ON a.id = r.agent_id
                 WHERE 1=1
                """);
        var params = new ArrayList<Object>();
        if (tenant != null) { sql.append(" AND t.name = ?"); params.add(tenant); }
        if (agent != null)  { sql.append(" AND a.name = ?"); params.add(agent); }
        if (statuses != null && !statuses.isEmpty()) {
            sql.append(" AND r.status = ANY(?)");
            params.add(statuses.toArray(new String[0]));
        }
        if (from != null) { sql.append(" AND r.started_at >= ?"); params.add(java.sql.Timestamp.from(from)); }
        if (to != null)   { sql.append(" AND r.started_at <  ?"); params.add(java.sql.Timestamp.from(to)); }
        var spec = jdbc.sql(sql.toString());
        for (Object p : params) spec = spec.param(p);
        return spec.query(Long.class).single();
    }

    public List<AdminLlmCallRow> findLlmCalls(String tenant, String realm, String purpose,
                                              String provider, String model, String endpoint,
                                              List<String> statuses, String runId,
                                              Instant from, Instant to,
                                              int limit, int offset) {
        var sql = new StringBuilder("""
                SELECT c.id, t.name AS tenant, c.run_id, c.purpose, c.realm,
                       c.provider, c.model, c.endpoint,
                       c.input_tokens, c.output_tokens,
                       c.cache_creation_input_tokens, c.cache_read_input_tokens,
                       c.cost_micros, c.duration_ms, c.status, c.error_code, c.created_at
                  FROM vistierie.llm_calls c
                  JOIN vistierie.tenants t ON t.id = c.tenant_id
                 WHERE 1=1
                """);
        var params = new ArrayList<Object>();
        if (tenant != null)   { sql.append(" AND t.name = ?"); params.add(tenant); }
        if (realm != null)    { sql.append(" AND c.realm = ?"); params.add(realm); }
        if (purpose != null)  { sql.append(" AND c.purpose = ?"); params.add(purpose); }
        if (provider != null) { sql.append(" AND c.provider = ?"); params.add(provider); }
        if (model != null)    { sql.append(" AND c.model = ?"); params.add(model); }
        if (endpoint != null) { sql.append(" AND c.endpoint = ?"); params.add(endpoint); }
        if (statuses != null && !statuses.isEmpty()) {
            sql.append(" AND c.status = ANY(?)");
            params.add(statuses.toArray(new String[0]));
        }
        if (runId != null) { sql.append(" AND c.run_id = ?"); params.add(runId); }
        if (from != null)  { sql.append(" AND c.created_at >= ?"); params.add(java.sql.Timestamp.from(from)); }
        if (to != null)    { sql.append(" AND c.created_at <  ?"); params.add(java.sql.Timestamp.from(to)); }
        sql.append(" ORDER BY c.created_at DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        var spec = jdbc.sql(sql.toString());
        for (Object p : params) spec = spec.param(p);
        return spec.query(this::mapLlmCall).list();
    }

    private AdminRunSummary mapRunSummary(ResultSet rs, int rn) throws SQLException {
        return new AdminRunSummary(
                rs.getString("id"),
                rs.getString("tenant"),
                rs.getString("agent"),
                rs.getString("trigger"),
                rs.getString("status"),
                rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
                rs.getObject("duration_ms") == null ? null : rs.getLong("duration_ms"),
                rs.getInt("llm_calls_count"),
                rs.getLong("total_cost_micros"));
    }

    private AdminLlmCallRow mapLlmCall(ResultSet rs, int rn) throws SQLException {
        return new AdminLlmCallRow(
                rs.getString("id"),
                rs.getString("tenant"),
                rs.getString("run_id"),
                rs.getString("purpose"),
                rs.getString("realm"),
                rs.getString("provider"),
                rs.getString("model"),
                rs.getString("endpoint"),
                rs.getInt("input_tokens"),
                rs.getInt("output_tokens"),
                rs.getInt("cache_creation_input_tokens"),
                rs.getInt("cache_read_input_tokens"),
                rs.getLong("cost_micros"),
                rs.getInt("duration_ms"),
                rs.getString("status"),
                rs.getString("error_code"),
                rs.getTimestamp("created_at").toInstant());
    }
}
