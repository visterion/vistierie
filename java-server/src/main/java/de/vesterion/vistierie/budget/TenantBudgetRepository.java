package de.vesterion.vistierie.budget;

import de.vesterion.vistierie.budget.admin.dto.BudgetPatchRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class TenantBudgetRepository {

    private final JdbcClient jdbc;

    public TenantBudgetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<BudgetPolicy> findByTenantId(UUID tenantId) {
        return jdbc.sql("""
                SELECT daily_cap_micros, monthly_cap_micros,
                       daily_warn_percent, monthly_warn_percent
                FROM vistierie.tenant_budgets
                WHERE tenant_id = ?
                """).param(tenantId).query((rs, n) -> new BudgetPolicy(
                getLongOrNull(rs, "daily_cap_micros"),
                getLongOrNull(rs, "monthly_cap_micros"),
                getIntOrNull(rs, "daily_warn_percent"),
                getIntOrNull(rs, "monthly_warn_percent")
        )).optional();
    }

    public void patch(UUID tenantId, BudgetPatchRequest req) {
        BudgetPolicy current = findByTenantId(tenantId).orElse(new BudgetPolicy(null, null, null, null));
        Long dailyCapMicros = req.hasDailyCapMicros() ? req.daily_cap_micros() : current.dailyCapMicros();
        Long monthlyCapMicros = req.hasMonthlyCapMicros() ? req.monthly_cap_micros() : current.monthlyCapMicros();
        Integer dailyWarnPercent = req.hasDailyWarnPercent() ? req.daily_warn_percent() : current.dailyWarnPercent();
        Integer monthlyWarnPercent = req.hasMonthlyWarnPercent() ? req.monthly_warn_percent() : current.monthlyWarnPercent();

        jdbc.sql("""
                INSERT INTO vistierie.tenant_budgets
                  (tenant_id, daily_cap_micros, monthly_cap_micros, daily_warn_percent, monthly_warn_percent)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id) DO UPDATE
                SET daily_cap_micros = EXCLUDED.daily_cap_micros,
                    monthly_cap_micros = EXCLUDED.monthly_cap_micros,
                    daily_warn_percent = EXCLUDED.daily_warn_percent,
                    monthly_warn_percent = EXCLUDED.monthly_warn_percent,
                    updated_at = now()
                """)
                .params(tenantId, dailyCapMicros, monthlyCapMicros, dailyWarnPercent, monthlyWarnPercent)
                .update();
    }

    private static Long getLongOrNull(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer getIntOrNull(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
