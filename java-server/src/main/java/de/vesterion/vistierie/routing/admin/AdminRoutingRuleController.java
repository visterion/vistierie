package de.vesterion.vistierie.routing.admin;

import de.vesterion.vistierie.routing.admin.dto.CreateRoutingRuleRequest;
import de.vesterion.vistierie.routing.admin.dto.PatchRoutingRuleRequest;
import de.vesterion.vistierie.routing.admin.dto.RoutingRuleResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/admin/routing-rules")
public class AdminRoutingRuleController {

    private final AdminRoutingRuleService svc;

    public AdminRoutingRuleController(AdminRoutingRuleService svc) {
        this.svc = svc;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoutingRuleResponse create(@Valid @RequestBody CreateRoutingRuleRequest req) {
        try {
            var r = svc.create(req.tenant(), req.realm(), req.purpose(),
                    req.provider(), req.model(),
                    req.fallback_provider(), req.fallback_model(), req.effort(),
                    req.priority(), req.allow_override(), req.locked());
            return RoutingRuleResponse.of(r);
        } catch (AdminRoutingRuleService.ConflictException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (AdminRoutingRuleService.BadInputException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping
    public List<RoutingRuleResponse> list(
            @RequestParam(required = false) String tenant,
            @RequestParam(required = false) String realm,
            @RequestParam(required = false) String purpose) {
        try {
            return svc.list(tenant, realm, purpose).stream()
                    .map(RoutingRuleResponse::of).toList();
        } catch (AdminRoutingRuleService.BadInputException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public RoutingRuleResponse get(@PathVariable UUID id) {
        try {
            return RoutingRuleResponse.of(svc.get(id));
        } catch (AdminRoutingRuleService.BadInputException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PatchMapping("/{id}")
    public RoutingRuleResponse patch(@PathVariable UUID id,
                                     @RequestBody PatchRoutingRuleRequest req) {
        try {
            var r = svc.patch(id, req.provider(), req.model(),
                    req.fallback_provider(), req.fallback_model(), req.clear_fallback(),
                    req.effort(), req.clear_effort(),
                    req.priority(), req.allow_override(), req.locked());
            return RoutingRuleResponse.of(r);
        } catch (AdminRoutingRuleService.BadInputException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        try {
            svc.delete(id);
        } catch (AdminRoutingRuleService.LastDefaultException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        } catch (AdminRoutingRuleService.BadInputException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }
}
