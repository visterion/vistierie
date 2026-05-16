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
        var req = new BudgetPatchRequest();
        if (node.has("daily_cap_micros")) {
            req.dailyCapMicros = node.get("daily_cap_micros").isNull() ? null : node.get("daily_cap_micros").longValue();
            req.hasDailyCapMicros = true;
        }
        if (node.has("monthly_cap_micros")) {
            req.monthlyCapMicros = node.get("monthly_cap_micros").isNull() ? null : node.get("monthly_cap_micros").longValue();
            req.hasMonthlyCapMicros = true;
        }
        if (node.has("daily_warn_percent")) {
            req.dailyWarnPercent = node.get("daily_warn_percent").isNull() ? null : node.get("daily_warn_percent").intValue();
            req.hasDailyWarnPercent = true;
        }
        if (node.has("monthly_warn_percent")) {
            req.monthlyWarnPercent = node.get("monthly_warn_percent").isNull() ? null : node.get("monthly_warn_percent").intValue();
            req.hasMonthlyWarnPercent = true;
        }
        return req;
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
