package de.vesterion.vistierie.budget.admin;

import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.budget.AgentBudgetRepository;
import de.vesterion.vistierie.budget.BudgetUsageRepository;
import de.vesterion.vistierie.budget.admin.dto.BudgetPatchRequest;
import de.vesterion.vistierie.budget.admin.dto.BudgetStatusResponse;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/admin/tenants/{tenant}/agents/{agent}/budget")
public class AdminAgentBudgetController {

    private final TenantRepository tenants;
    private final AgentRepository agents;
    private final AgentBudgetRepository budgets;
    private final BudgetUsageRepository usage;

    public AdminAgentBudgetController(TenantRepository tenants, AgentRepository agents,
                                      AgentBudgetRepository budgets, BudgetUsageRepository usage) {
        this.tenants = tenants;
        this.agents = agents;
        this.budgets = budgets;
        this.usage = usage;
    }

    @GetMapping
    public BudgetStatusResponse get(@PathVariable String tenant, @PathVariable String agent) {
        var resolved = agents.findByName(
                tenants.findByName(tenant)
                        .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "tenant not found: " + tenant))
                        .id(),
                agent
        ).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "agent not found: " + agent));
        var current = usage.usageForAgent(resolved.id(), Instant.now());
        return budgets.findByAgentId(resolved.id())
                .map(policy -> BudgetStatusResponse.fromPolicy(policy, current.dailyMicros(), current.monthlyMicros()))
                .orElseGet(() -> BudgetStatusResponse.usageOnly(current.dailyMicros(), current.monthlyMicros()));
    }

    @PatchMapping
    public BudgetStatusResponse patch(@PathVariable String tenant, @PathVariable String agent,
                                      @RequestBody JsonNode body) {
        var resolved = agents.findByName(
                tenants.findByName(tenant)
                        .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "tenant not found: " + tenant))
                        .id(),
                agent
        ).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "agent not found: " + agent));
        try {
            var req = BudgetPatchRequest.fromJson(body);
            budgets.patch(resolved.id(), req);
            var current = usage.usageForAgent(resolved.id(), Instant.now());
            return budgets.findByAgentId(resolved.id())
                    .map(policy -> BudgetStatusResponse.fromPolicy(policy, current.dailyMicros(), current.monthlyMicros()))
                    .orElseGet(() -> BudgetStatusResponse.usageOnly(current.dailyMicros(), current.monthlyMicros()));
        } catch (IllegalArgumentException | DataIntegrityViolationException e) {
            throw new ResponseStatusException(BAD_REQUEST, e.getMessage());
        }
    }
}
