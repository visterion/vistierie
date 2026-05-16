package de.vesterion.vistierie.budget;

public record BudgetPolicy(
        Long dailyCapMicros,
        Long monthlyCapMicros,
        Integer dailyWarnPercent,
        Integer monthlyWarnPercent
) {
    public boolean operational() {
        return dailyCapMicros != null || monthlyCapMicros != null;
    }
}
