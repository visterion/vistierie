package de.vesterion.vistierie.budget;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

@Repository
public class BudgetUsageRepository {

    private final JdbcClient jdbc;

    public BudgetUsageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Usage(long dailyMicros, long monthlyMicros) {}

    public Usage usageForTenant(UUID tenantId, Instant now) {
        return new Usage(
                sumForTenant(tenantId, startOfUtcDay(now), startOfNextUtcDay(now)),
                sumForTenant(tenantId, startOfUtcMonth(now), startOfNextUtcMonth(now))
        );
    }

    public Usage usageForAgent(UUID agentId, Instant now) {
        return new Usage(
                sumForAgent(agentId, startOfUtcDay(now), startOfNextUtcDay(now)),
                sumForAgent(agentId, startOfUtcMonth(now), startOfNextUtcMonth(now))
        );
    }

    private long sumForTenant(UUID tenantId, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(cost_micros), 0)
                FROM vistierie.llm_calls
                WHERE tenant_id = ?
                  AND created_at >= ?
                  AND created_at < ?
                """)
                .params(tenantId, Timestamp.from(from), Timestamp.from(to))
                .query(Long.class)
                .single();
    }

    private long sumForAgent(UUID agentId, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(cost_micros), 0)
                FROM vistierie.llm_calls
                WHERE agent_id = ?
                  AND created_at >= ?
                  AND created_at < ?
                """)
                .params(agentId, Timestamp.from(from), Timestamp.from(to))
                .query(Long.class)
                .single();
    }

    private static Instant startOfUtcDay(Instant now) {
        return now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Instant startOfNextUtcDay(Instant now) {
        return startOfUtcDay(now).plusSeconds(24 * 60 * 60);
    }

    private static Instant startOfUtcMonth(Instant now) {
        var zoned = now.atZone(ZoneOffset.UTC);
        return ZonedDateTime.of(zoned.getYear(), zoned.getMonthValue(), 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant();
    }

    private static Instant startOfNextUtcMonth(Instant now) {
        return startOfUtcMonth(now).atZone(ZoneOffset.UTC).plusMonths(1).toInstant();
    }
}
