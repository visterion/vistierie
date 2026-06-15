package de.vesterion.vistierie.audit;

import de.vesterion.vistierie.provider.ProviderRequest;
import de.vesterion.vistierie.provider.ProviderResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

@Component
public class LlmCallRecorder {
    private final JdbcClient jdbc;
    private final LlmCallBodyRepository bodyRepo;
    private final ImageRedactor redactor;
    private final ObjectMapper json;

    public LlmCallRecorder(JdbcClient jdbc, LlmCallBodyRepository bodyRepo,
                           ImageRedactor redactor, ObjectMapper json) {
        this.jdbc = jdbc;
        this.bodyRepo = bodyRepo;
        this.redactor = redactor;
        this.json = json;
    }

    public record Row(
            String id, UUID tenantId, UUID agentId, String purpose, String realm,
            String provider, String model, String endpoint,
            int inputTokens, int outputTokens, int cacheCreate, int cacheRead,
            long costMicros, int durationMs, String status, String errorCode,
            String runId,
            String batchId) {}

    @Transactional
    public void insertWithBody(Row row, ProviderRequest req, ProviderResponse res) {
        insert(row);
        var redacted = redactor.redact(req);
        var node = json.valueToTree(redacted);
        String responseText = res == null ? null : res.text();
        var contentBlocks = res == null ? null : res.contentBlocks();
        bodyRepo.insert(row.id(), node, responseText, contentBlocks, Instant.now());
    }

    public void insert(Row r) {
        jdbc.sql("""
                INSERT INTO vistierie.llm_calls
                  (id, tenant_id, agent_id, purpose, realm, provider, model, endpoint,
                   input_tokens, output_tokens, cache_creation_input_tokens, cache_read_input_tokens,
                   cost_micros, duration_ms, status, error_code, run_id, batch_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)
                .params(r.id(), r.tenantId(), r.agentId(), r.purpose(), r.realm(),
                        r.provider(), r.model(), r.endpoint(),
                        r.inputTokens(), r.outputTokens(), r.cacheCreate(), r.cacheRead(),
                        r.costMicros(), r.durationMs(), r.status(), r.errorCode(),
                        r.runId(), r.batchId())
                .update();
    }
}
