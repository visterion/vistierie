package de.vesterion.vistierie.budget.admin.dto;

import de.vesterion.vistierie.budget.BudgetPolicy;

public record BudgetStatusResponse(
        Long daily_cap_micros,
        Long monthly_cap_micros,
        Integer daily_warn_percent,
        Integer monthly_warn_percent,
        long daily_usage_micros,
        long monthly_usage_micros,
        Long daily_remaining_micros,
        Long monthly_remaining_micros,
        boolean daily_warned,
        boolean monthly_warned,
        boolean daily_blocked,
        boolean monthly_blocked
) {
    public static BudgetStatusResponse fromPolicy(BudgetPolicy policy) {
        return new BudgetStatusResponse(
                policy.dailyCapMicros(),
                policy.monthlyCapMicros(),
                policy.dailyWarnPercent(),
                policy.monthlyWarnPercent(),
                0L,
                0L,
                policy.dailyCapMicros(),
                policy.monthlyCapMicros(),
                false,
                false,
                false,
                false
        );
    }

    public static BudgetStatusResponse empty() {
        return new BudgetStatusResponse(null, null, null, null, 0L, 0L, null, null, false, false, false, false);
    }
}
