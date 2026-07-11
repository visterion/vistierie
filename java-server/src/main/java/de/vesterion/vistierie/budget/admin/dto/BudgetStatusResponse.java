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
    public static BudgetStatusResponse fromPolicy(BudgetPolicy policy, long dailyUsageMicros, long monthlyUsageMicros) {
        Long dailyRemainingMicros = remaining(policy.dailyCapMicros(), dailyUsageMicros);
        Long monthlyRemainingMicros = remaining(policy.monthlyCapMicros(), monthlyUsageMicros);
        return new BudgetStatusResponse(
                policy.dailyCapMicros(),
                policy.monthlyCapMicros(),
                policy.dailyWarnPercent(),
                policy.monthlyWarnPercent(),
                dailyUsageMicros,
                monthlyUsageMicros,
                dailyRemainingMicros,
                monthlyRemainingMicros,
                warned(policy.dailyCapMicros(), policy.dailyWarnPercent(), dailyUsageMicros),
                warned(policy.monthlyCapMicros(), policy.monthlyWarnPercent(), monthlyUsageMicros),
                blocked(policy.dailyCapMicros(), dailyUsageMicros),
                blocked(policy.monthlyCapMicros(), monthlyUsageMicros)
        );
    }

    public static BudgetStatusResponse empty() {
        return new BudgetStatusResponse(null, null, null, null, 0L, 0L, null, null, false, false, false, false);
    }

    /** Status for an agent without a configured budget policy: real usage, no caps. */
    public static BudgetStatusResponse usageOnly(long dailyUsageMicros, long monthlyUsageMicros) {
        return new BudgetStatusResponse(null, null, null, null,
                dailyUsageMicros, monthlyUsageMicros,
                null, null, false, false, false, false);
    }

    private static Long remaining(Long capMicros, long usageMicros) {
        if (capMicros == null) return null;
        long remaining = capMicros - usageMicros;
        return remaining >= 0 ? remaining : null;
    }

    private static boolean warned(Long capMicros, Integer warnPercent, long usageMicros) {
        if (capMicros == null || warnPercent == null) return false;
        return usageMicros * 100L >= capMicros * warnPercent;
    }

    private static boolean blocked(Long capMicros, long usageMicros) {
        return capMicros != null && usageMicros >= capMicros;
    }
}
