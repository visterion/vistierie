package de.vesterion.vistierie.budget;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

@Component
public class BudgetHeaderWriter {

    public void write(HttpServletResponse response, BudgetEnforcer.BudgetCheckResult budget) {
        setIfPresent(response, "X-Vistierie-Tenant-Daily-Budget-Remaining-Micros", budget.tenantDailyRemaining());
        setIfPresent(response, "X-Vistierie-Tenant-Monthly-Budget-Remaining-Micros", budget.tenantMonthlyRemaining());
        setIfPresent(response, "X-Vistierie-Agent-Daily-Budget-Remaining-Micros", budget.agentDailyRemaining());
        setIfPresent(response, "X-Vistierie-Agent-Monthly-Budget-Remaining-Micros", budget.agentMonthlyRemaining());
    }

    private static void setIfPresent(HttpServletResponse response, String name, Long value) {
        if (value != null) {
            response.setHeader(name, Long.toString(value));
        }
    }
}
