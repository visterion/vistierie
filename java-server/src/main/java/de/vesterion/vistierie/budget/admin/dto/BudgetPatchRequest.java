package de.vesterion.vistierie.budget.admin.dto;

import tools.jackson.databind.JsonNode;

public class BudgetPatchRequest {

    private Long dailyCapMicros;
    private Long monthlyCapMicros;
    private Integer dailyWarnPercent;
    private Integer monthlyWarnPercent;

    private boolean hasDailyCapMicros;
    private boolean hasMonthlyCapMicros;
    private boolean hasDailyWarnPercent;
    private boolean hasMonthlyWarnPercent;

    public BudgetPatchRequest() {
    }

    public BudgetPatchRequest(Long dailyCapMicros, Long monthlyCapMicros,
                              Integer dailyWarnPercent, Integer monthlyWarnPercent) {
        this.dailyCapMicros = dailyCapMicros;
        this.monthlyCapMicros = monthlyCapMicros;
        this.dailyWarnPercent = dailyWarnPercent;
        this.monthlyWarnPercent = monthlyWarnPercent;
        this.hasDailyCapMicros = true;
        this.hasMonthlyCapMicros = true;
        this.hasDailyWarnPercent = true;
        this.hasMonthlyWarnPercent = true;
    }

    public static BudgetPatchRequest fromJson(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("budget patch body must be a JSON object");
        }
        var req = new BudgetPatchRequest();
        if (node.has("daily_cap_micros")) {
            req.dailyCapMicros = longOrNull(node, "daily_cap_micros");
            req.hasDailyCapMicros = true;
        }
        if (node.has("monthly_cap_micros")) {
            req.monthlyCapMicros = longOrNull(node, "monthly_cap_micros");
            req.hasMonthlyCapMicros = true;
        }
        if (node.has("daily_warn_percent")) {
            req.dailyWarnPercent = percentOrNull(node, "daily_warn_percent");
            req.hasDailyWarnPercent = true;
        }
        if (node.has("monthly_warn_percent")) {
            req.monthlyWarnPercent = percentOrNull(node, "monthly_warn_percent");
            req.hasMonthlyWarnPercent = true;
        }
        return req;
    }

    private static Long longOrNull(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber()) {
            throw new IllegalArgumentException(field + " must be an integer or null");
        }
        return value.longValue();
    }

    private static Integer percentOrNull(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber()) {
            throw new IllegalArgumentException(field + " must be an integer or null");
        }
        int parsed = value.intValue();
        if (parsed < 1 || parsed > 100) {
            throw new IllegalArgumentException(field + " must be between 1 and 100");
        }
        return parsed;
    }

    public Long daily_cap_micros() {
        return dailyCapMicros;
    }

    public Long monthly_cap_micros() {
        return monthlyCapMicros;
    }

    public Integer daily_warn_percent() {
        return dailyWarnPercent;
    }

    public Integer monthly_warn_percent() {
        return monthlyWarnPercent;
    }

    public boolean hasDailyCapMicros() {
        return hasDailyCapMicros;
    }

    public boolean hasMonthlyCapMicros() {
        return hasMonthlyCapMicros;
    }

    public boolean hasDailyWarnPercent() {
        return hasDailyWarnPercent;
    }

    public boolean hasMonthlyWarnPercent() {
        return hasMonthlyWarnPercent;
    }
}
