package de.vesterion.vistierie.budget.admin;

import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.budget.AgentBudgetRepository;
import de.vesterion.vistierie.budget.admin.dto.BudgetPatchRequest;
import de.vesterion.vistierie.budget.admin.dto.BudgetStatusResponse;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/admin/tenants/{tenant}/agents/{agent}/budget")
public class AdminAgentBudgetController {

    private final TenantRepository tenants;
    private final AgentRepository agents;
    private final AgentBudgetRepository budgets;

    public AdminAgentBudgetController(TenantRepository tenants, AgentRepository agents, AgentBudgetRepository budgets) {
        this.tenants = tenants;
        this.agents = agents;
        this.budgets = budgets;
    }

    @GetMapping
    public BudgetStatusResponse get(@PathVariable String tenant, @PathVariable String agent) {
        var resolved = agents.findByName(
                tenants.findByName(tenant)
                        .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "tenant not found: " + tenant))
                        .id(),
                agent
        ).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "agent not found: " + agent));
        return budgets.findByAgentId(resolved.id())
                .map(BudgetStatusResponse::fromPolicy)
                .orElseGet(BudgetStatusResponse::empty);
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
        var req = BudgetPatchRequest.fromJson(body);
        budgets.patch(resolved.id(), req);
        return budgets.findByAgentId(resolved.id())
                .map(BudgetStatusResponse::fromPolicy)
                .orElseGet(BudgetStatusResponse::empty);
    }
}
