package de.vesterion.vistierie.budget;

import org.springframework.http.HttpStatus;

public class BudgetException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String tenant;
    private final String agentName;
    private final String period;
    private final Long remainingMicros;

    private BudgetException(HttpStatus status, String code, String message,
                            String tenant, String agentName, String period, Long remainingMicros) {
        super(message);
        this.status = status;
        this.code = code;
        this.tenant = tenant;
        this.agentName = agentName;
        this.period = period;
        this.remainingMicros = remainingMicros;
    }

    public static BudgetException missingTenant(String tenant, String agentName) {
        return new BudgetException(HttpStatus.FORBIDDEN, "budget_missing_tenant",
                "tenant budget missing for tenant " + tenant + " and agent " + agentName,
                tenant, agentName, null, null);
    }

    public static BudgetException missingAgent(String tenant, String agentName) {
        return new BudgetException(HttpStatus.FORBIDDEN, "budget_missing_agent",
                "agent budget missing for tenant " + tenant + " and agent " + agentName,
                tenant, agentName, null, null);
    }

    public static BudgetException agentNotFound(String tenant, String agentName) {
        return new BudgetException(HttpStatus.FORBIDDEN, "budget_missing_agent",
                "agent not found for tenant " + tenant + ": " + agentName,
                tenant, agentName, null, null);
    }

    public static BudgetException exceeded(String scope, String period, String tenant, String agentName) {
        return new BudgetException(HttpStatus.FORBIDDEN,
                "budget_exceeded_" + scope + "_" + period,
                scope + " " + period + " budget exceeded for tenant " + tenant + " and agent " + agentName,
                tenant, agentName, period, 0L);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String tenant() {
        return tenant;
    }

    public String agentName() {
        return agentName;
    }

    public String period() {
        return period;
    }

    public Long remainingMicros() {
        return remainingMicros;
    }
}
