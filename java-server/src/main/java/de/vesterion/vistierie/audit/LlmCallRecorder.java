package de.vesterion.vistierie.audit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class LlmCallRecorder {
    private final JdbcClient jdbc;
    public LlmCallRecorder(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record Row(
            String id, UUID tenantId, String purpose, String realm,
            String provider, String model, String endpoint,
            int inputTokens, int outputTokens, int cacheCreate, int cacheRead,
            long costMicros, int durationMs, String status, String errorCode) {}

    public void insert(Row r) {
        jdbc.sql("""
                INSERT INTO vistierie.llm_calls
                  (id, tenant_id, purpose, realm, provider, model, endpoint,
                   input_tokens, output_tokens, cache_creation_input_tokens, cache_read_input_tokens,
                   cost_micros, duration_ms, status, error_code)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)
                .params(r.id(), r.tenantId(), r.purpose(), r.realm(),
                        r.provider(), r.model(), r.endpoint(),
                        r.inputTokens(), r.outputTokens(), r.cacheCreate(), r.cacheRead(),
                        r.costMicros(), r.durationMs(), r.status(), r.errorCode())
                .update();
    }
}
