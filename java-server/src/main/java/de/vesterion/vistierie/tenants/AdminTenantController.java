package de.vesterion.vistierie.tenants;

import de.vesterion.vistierie.routing.RoutingRule;
import de.vesterion.vistierie.routing.RoutingRuleRepository;
import de.vesterion.vistierie.routing.RoutingResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/admin/tenants")
public class AdminTenantController {

    private final TenantRepository repo;
    private final BCryptPasswordEncoder enc;
    private final SecureRandom rng = new SecureRandom();
    private final RoutingRuleRepository routingRules;
    private final RoutingResolver routingResolver;

    public AdminTenantController(TenantRepository repo, BCryptPasswordEncoder enc,
                                 RoutingRuleRepository routingRules, RoutingResolver routingResolver) {
        this.repo = repo;
        this.enc = enc;
        this.routingRules = routingRules;
        this.routingResolver = routingResolver;
    }

    @GetMapping
    public List<TenantSummary> list() {
        return repo.findAll().stream()
                .map(t -> new TenantSummary(t.id().toString(), t.name(),
                        t.killUntil() != null, t.killReason()))
                .toList();
    }

    @PostMapping
    public ResponseEntity<CreatedTenant> create(@Valid @RequestBody CreateRequest req) {
        var id = UUID.randomUUID();
        var bytes = new byte[24];
        rng.nextBytes(bytes);
        var token = HexFormat.of().formatHex(bytes);
        repo.insert(id, req.name(), enc.encode(token));
        var now = Instant.now();
        routingRules.insert(new RoutingRule(
                UUID.randomUUID(),
                id,
                null, null,
                "anthropic", "claude-sonnet-4-6", 1000,
                false, false, now, now));
        routingResolver.bumpVersion();
        return ResponseEntity.status(201).body(new CreatedTenant(id.toString(), req.name(), token));
    }

    @PostMapping("/{name}/kill")
    public ResponseEntity<Void> kill(@PathVariable String name, @Valid @RequestBody KillRequest req) {
        var t = repo.findByName(name).orElseThrow();
        var until = req.until() == null ? Instant.parse("9999-12-31T23:59:59Z") : req.until();
        repo.setKill(t.id(), until, req.reason(), req.setBy() == null ? "admin" : req.setBy());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{name}/kill")
    public ResponseEntity<Void> clearKill(@PathVariable String name) {
        var t = repo.findByName(name).orElseThrow();
        repo.clearKill(t.id());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{name}/kill")
    public KillStatus killStatus(@PathVariable String name) {
        var t = repo.findByName(name).orElseThrow();
        return new KillStatus(t.killUntil(), t.killReason(), t.killSetBy());
    }

    public record CreateRequest(@NotBlank String name) {}
    public record CreatedTenant(String id, String name, String token) {}
    public record KillRequest(@NotBlank String reason, Instant until, String setBy) {}
    public record KillStatus(Instant until, String reason, String setBy) {}
    public record TenantSummary(String id, String name, boolean killed, String killReason) {}
}
