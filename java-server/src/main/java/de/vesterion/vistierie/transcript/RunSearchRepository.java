package de.vesterion.vistierie.transcript;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class RunSearchRepository {

    public record Hit(String runId, String agent, String status, boolean hasError,
                      Instant startedAt, double rank, String snippet) {}

    private final JdbcClient jdbc;

    public RunSearchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(String runId, UUID tenantId, UUID agentId, String agentName,
                       String status, boolean hasError, Instant startedAt, String body) {
        jdbc.sql("""
                INSERT INTO vistierie.run_search_doc
                  (run_id, tenant_id, agent_id, agent_name, status, has_error, started_at, body, tsv, excerpt)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, to_tsvector('simple', ?), left(?, 500))
                ON CONFLICT (run_id) DO UPDATE SET
                  status = EXCLUDED.status,
                  has_error = EXCLUDED.has_error,
                  body = EXCLUDED.body,
                  tsv = EXCLUDED.tsv,
                  excerpt = EXCLUDED.excerpt
                """).params(runId, tenantId, agentId, agentName, status, hasError,
                        Timestamp.from(startedAt), body, body, body)
                .update();
    }

    public List<Hit> search(UUID tenantId, String q, String agent, List<String> statuses,
                            Boolean hasError, Instant from, Instant to, int limit, int offset) {
        var sql = new StringBuilder("""
                SELECT run_id, agent_name, status, has_error, started_at,
                       ts_rank(tsv, plainto_tsquery('simple', ?)) AS rank,
                       ts_headline('simple', body, plainto_tsquery('simple', ?),
                           'MaxFragments=2,MinWords=3,MaxWords=18') AS snippet
                FROM vistierie.run_search_doc
                WHERE tenant_id = ? AND tsv @@ plainto_tsquery('simple', ?)
                """);
        var params = new ArrayList<Object>();
        params.add(q); params.add(q); params.add(tenantId); params.add(q);
        if (agent != null)   { sql.append(" AND agent_name = ?"); params.add(agent); }
        if (hasError != null){ sql.append(" AND has_error = ?"); params.add(hasError); }
        if (statuses != null && !statuses.isEmpty()) {
            sql.append(" AND status = ANY(?)"); params.add(statuses.toArray(new String[0]));
        }
        if (from != null) { sql.append(" AND started_at >= ?"); params.add(Timestamp.from(from)); }
        if (to != null)   { sql.append(" AND started_at <  ?"); params.add(Timestamp.from(to)); }
        sql.append(" ORDER BY rank DESC, started_at DESC LIMIT ? OFFSET ?");
        params.add(limit); params.add(offset);

        var spec = jdbc.sql(sql.toString());
        for (Object p : params) spec = spec.param(p);
        return spec.query(this::map).list();
    }

    private Hit map(ResultSet rs, int n) throws SQLException {
        return new Hit(
                rs.getString("run_id"),
                rs.getString("agent_name"),
                rs.getString("status"),
                rs.getBoolean("has_error"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getDouble("rank"),
                rs.getString("snippet"));
    }
}
