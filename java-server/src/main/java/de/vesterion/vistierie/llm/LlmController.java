package de.vesterion.vistierie.llm;

import de.vesterion.vistierie.auth.AuthExceptions;
import de.vesterion.vistierie.kill.KillSwitchService;
import de.vesterion.vistierie.llm.dto.CompleteRequest;
import de.vesterion.vistierie.llm.dto.LlmResponse;
import de.vesterion.vistierie.llm.dto.VisionRequest;
import de.vesterion.vistierie.provider.LlmProvider;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/llm")
public class LlmController {

    private final LlmService svc;
    public LlmController(LlmService svc) { this.svc = svc; }

    @PostMapping("/complete")
    public LlmResponse complete(@Valid @RequestBody CompleteRequest req) {
        return svc.complete(req);
    }

    @PostMapping("/vision")
    public LlmResponse vision(@Valid @RequestBody VisionRequest req) {
        return svc.vision(req);
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

    @ExceptionHandler(AuthExceptions.Unauthorized.class)
    public ResponseEntity<Map<String, Object>> unauth(AuthExceptions.Unauthorized e) {
        return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
    }
}
