package de.vesterion.vistierie.audit.admin;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Repository
public class CostAggregationRepository {

    private static final Set<String> ALLOWED_GROUP_BY = Set.of(
            "tenant", "realm", "purpose", "provider", "model", "endpoint", "status");

    private final JdbcClient jdbc;

    public CostAggregationRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record AggregatedRow(
            Instant bucket,
            Map<String, String> groupValues,
            long calls,
            long inputTokens, long outputTokens,
            long cacheCreationInputTokens, long cacheReadInputTokens,
            long costMicros
    ) {}

    public record Query(
            Instant from, Instant to,
            String granularity,
            List<String> groupBy,
            String tenant, String realm, String purpose,
            String provider, String model, String endpoint,
            List<String> statuses
    ) {
        public void validate() {
            if (from == null || to == null) throw new IllegalArgumentException("from/to required");
            if (!from.isBefore(to)) throw new IllegalArgumentException("from must be before to");
            if (!Set.of("hour", "day", "none").contains(granularity)) {
                throw new IllegalArgumentException("granularity must be hour|day|none");
            }
            for (var g : groupBy) {
                if (!ALLOWED_GROUP_BY.contains(g)) {
                    throw new IllegalArgumentException("unknown group_by: " + g);
                }
            }
        }
    }

    public List<AggregatedRow> query(Query q) {
        q.validate();

        var sql = new StringBuilder("SELECT ");
        var selects = new ArrayList<String>();
        var groupCols = new ArrayList<String>();

        if (!"none".equals(q.granularity())) {
            String trunc = q.granularity().equals("hour") ? "hour" : "day";
            selects.add("date_trunc('" + trunc + "', c.created_at) AS bucket");
            groupCols.add("bucket");
        }
        for (String dim : q.groupBy()) {
            String col = switch (dim) {
                case "tenant"   -> "t.name";
                case "realm"    -> "c.realm";
                case "purpose"  -> "c.purpose";
                case "provider" -> "c.provider";
                case "model"    -> "c.model";
                case "endpoint" -> "c.endpoint";
                case "status"   -> "c.status";
                default -> throw new IllegalStateException();
            };
            selects.add(col + " AS \"" + dim + "\"");
            groupCols.add(col);
        }
        selects.add("COUNT(*) AS calls");
        selects.add("SUM(c.input_tokens)::bigint AS input_tokens");
        selects.add("SUM(c.output_tokens)::bigint AS output_tokens");
        selects.add("SUM(c.cache_creation_input_tokens)::bigint AS cache_creation_input_tokens");
        selects.add("SUM(c.cache_read_input_tokens)::bigint AS cache_read_input_tokens");
        selects.add("SUM(c.cost_micros)::bigint AS cost_micros");

        sql.append(String.join(", ", selects));
        sql.append(" FROM vistierie.llm_calls c JOIN vistierie.tenants t ON t.id = c.tenant_id");
        sql.append(" WHERE c.created_at >= ? AND c.created_at < ?");

        var params = new ArrayList<Object>();
        params.add(java.sql.Timestamp.from(q.from()));
        params.add(java.sql.Timestamp.from(q.to()));

        if (q.tenant() != null)   { sql.append(" AND t.name = ?"); params.add(q.tenant()); }
        if (q.realm() != null)    { sql.append(" AND c.realm = ?"); params.add(q.realm()); }
        if (q.purpose() != null)  { sql.append(" AND c.purpose = ?"); params.add(q.purpose()); }
        if (q.provider() != null) { sql.append(" AND c.provider = ?"); params.add(q.provider()); }
        if (q.model() != null)    { sql.append(" AND c.model = ?"); params.add(q.model()); }
        if (q.endpoint() != null) { sql.append(" AND c.endpoint = ?"); params.add(q.endpoint()); }
        if (q.statuses() != null && !q.statuses().isEmpty()) {
            sql.append(" AND c.status = ANY(?)");
            params.add(q.statuses().toArray(new String[0]));
        }

        if (!groupCols.isEmpty()) {
            sql.append(" GROUP BY ").append(String.join(", ", groupCols));
            sql.append(" ORDER BY ").append(String.join(", ", groupCols));
        }

        var spec = jdbc.sql(sql.toString());
        for (Object p : params) spec = spec.param(p);

        return spec.query((rs, rn) -> mapRow(rs, q)).list();
    }

    private AggregatedRow mapRow(ResultSet rs, Query q) throws SQLException {
        Instant bucket = null;
        if (!"none".equals(q.granularity())) {
            var ts = rs.getTimestamp("bucket");
            bucket = ts == null ? null : ts.toInstant();
        }
        Map<String, String> groupVals = new HashMap<>();
        for (String dim : q.groupBy()) {
            groupVals.put(dim, rs.getString(dim));
        }
        return new AggregatedRow(
                bucket, groupVals,
                rs.getLong("calls"),
                rs.getLong("input_tokens"),
                rs.getLong("output_tokens"),
                rs.getLong("cache_creation_input_tokens"),
                rs.getLong("cache_read_input_tokens"),
                rs.getLong("cost_micros"));
    }
}
