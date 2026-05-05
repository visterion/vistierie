package de.vesterion.vistierie.health;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {
    private final JdbcClient jdbc;
    public HealthController(JdbcClient jdbc) { this.jdbc = jdbc; }

    @GetMapping("/healthz")
    public Map<String, String> live() { return Map.of("status", "ok"); }

    @GetMapping("/readyz")
    public Map<String, String> ready() {
        jdbc.sql("SELECT 1").query(Integer.class).single();
        return Map.of("status", "ready");
    }
}
