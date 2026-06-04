package de.vesterion.vistierie.llm;

import de.vesterion.vistierie.budget.BudgetException;
import de.vesterion.vistierie.budget.BudgetHeaderWriter;
import de.vesterion.vistierie.auth.AuthExceptions;
import de.vesterion.vistierie.kill.KillSwitchService;
import de.vesterion.vistierie.llm.dto.CompleteRequest;
import de.vesterion.vistierie.llm.dto.LlmResponse;
import de.vesterion.vistierie.llm.dto.MultiVisionRequest;
import de.vesterion.vistierie.llm.dto.VisionRequest;
import de.vesterion.vistierie.provider.LlmProvider;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/llm")
public class LlmController {

    private final LlmService svc;
    private final BudgetHeaderWriter headers;
    public LlmController(LlmService svc, BudgetHeaderWriter headers) {
        this.svc = svc;
        this.headers = headers;
    }

    @PostMapping("/complete")
    public LlmResponse complete(@Valid @RequestBody CompleteRequest req, HttpServletResponse response) {
        var result = svc.complete(req);
        headers.write(response, result.budget());
        return result.response();
    }

    @PostMapping("/vision")
    public LlmResponse vision(@Valid @RequestBody VisionRequest req, HttpServletResponse response) {
        var result = svc.vision(req);
        headers.write(response, result.budget());
        return result.response();
    }

    @PostMapping("/vision-multi")
    public LlmResponse visionMulti(@Valid @RequestBody MultiVisionRequest req, HttpServletResponse response) {
        var result = svc.visionMulti(req);
        headers.write(response, result.budget());
        return result.response();
    }

    @ExceptionHandler(KillSwitchService.KilledException.class)
    public ResponseEntity<Map<String, Object>> killed(KillSwitchService.KilledException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "killed", "reason", e.reason(),
                        "until", e.until().toString()));
    }

    @ExceptionHandler(LlmProvider.ProviderException.class)
    public ResponseEntity<Map<String, Object>> provider(LlmProvider.ProviderException e) {
        var status = e.statusCode() >= 500 ? HttpStatus.BAD_GATEWAY : HttpStatus.valueOf(e.statusCode());
        return ResponseEntity.status(status).body(Map.of(
                "error", "provider_error",
                "provider_status", e.statusCode(),
                "code", e.errorCode()));
    }

    @ExceptionHandler(BudgetException.class)
    public ResponseEntity<Map<String, Object>> budget(BudgetException e) {
        return ResponseEntity.status(e.status()).body(Map.of(
                "error", e.code(),
                "message", e.getMessage(),
                "tenant", e.tenant(),
                "agent_name", e.agentName()
        ));
    }

    @ExceptionHandler(AuthExceptions.Unauthorized.class)
    public ResponseEntity<Map<String, Object>> unauth(AuthExceptions.Unauthorized e) {
        return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
    }
}
