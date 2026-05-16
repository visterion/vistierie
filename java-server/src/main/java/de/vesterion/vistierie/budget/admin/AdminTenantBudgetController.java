package de.vesterion.vistierie.budget.admin;

import de.vesterion.vistierie.budget.TenantBudgetRepository;
import de.vesterion.vistierie.budget.admin.dto.BudgetPatchRequest;
import de.vesterion.vistierie.budget.admin.dto.BudgetStatusResponse;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/admin/tenants/{name}/budget")
public class AdminTenantBudgetController {

    private final TenantRepository tenants;
    private final TenantBudgetRepository budgets;

    public AdminTenantBudgetController(TenantRepository tenants, TenantBudgetRepository budgets) {
        this.tenants = tenants;
        this.budgets = budgets;
    }

    @GetMapping
    public BudgetStatusResponse get(@PathVariable String name) {
        var tenant = tenants.findByName(name)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "tenant not found: " + name));
        return budgets.findByTenantId(tenant.id())
                .map(BudgetStatusResponse::fromPolicy)
                .orElseGet(BudgetStatusResponse::empty);
    }

    @PatchMapping
    public BudgetStatusResponse patch(@PathVariable String name, @RequestBody JsonNode body) {
        var tenant = tenants.findByName(name)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "tenant not found: " + name));
        var req = BudgetPatchRequest.fromJson(body);
        budgets.patch(tenant.id(), req);
        return budgets.findByTenantId(tenant.id())
                .map(BudgetStatusResponse::fromPolicy)
                .orElseGet(BudgetStatusResponse::empty);
    }
}
