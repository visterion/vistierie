package de.vesterion.vistierie.tenants;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/tenants")
public class AdminTenantController {
    private final TenantRepository repo;
    public AdminTenantController(TenantRepository repo) { this.repo = repo; }

    @GetMapping
    public List<TenantSummary> list() {
        return repo.findAll().stream()
                .map(t -> new TenantSummary(t.id().toString(), t.name(),
                        t.killUntil() != null, t.killReason()))
                .toList();
    }

    public record TenantSummary(String id, String name, boolean killed, String killReason) {}
}
