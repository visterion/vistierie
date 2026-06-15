package de.vesterion.vistierie.audit;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LlmCallBodyRepositoryTest extends PostgresTestBase {

    @Autowired LlmCallBodyRepository repo;
    @Autowired TenantRepository tenants;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    UUID tenantId;
    String callId;

    @BeforeEach
    void seed() {
        tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tnt-" + tenantId.toString().substring(0, 8), "x");
        callId = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO vistierie.llm_calls
                  (id, tenant_id, purpose, provider, model, endpoint, status)
                VALUES (?, ?, 'p', 'anthropic', 'm', 'complete', 'ok')
                """).params(callId, tenantId).update();
    }

    @Test
    void insertAndFind() {
        var node = json.readTree("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        repo.insert(callId, node, "ok response", Instant.now());

        var found = repo.findByCallId(callId).orElseThrow();
        assertThat(found.callId()).isEqualTo(callId);
        assertThat(found.responseText()).isEqualTo("ok response");
        assertThat(found.requestJson().get("messages").get(0).get("content").asText()).isEqualTo("hi");
    }

    @Test
    void cascadeDeletesBodyWhenCallDeleted() {
        var node = json.readTree("{}");
        repo.insert(callId, node, null, Instant.now());
        jdbc.sql("DELETE FROM vistierie.llm_calls WHERE id = ?").param(callId).update();
        assertThat(repo.findByCallId(callId)).isEmpty();
    }

    @Test
    void persistsAndReadsResponseContentJson() {
        var req = json.readTree("{\"messages\":[]}");
        var content = json.readTree("[{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"x\",\"input\":{}}]");

        repo.insert(callId, req, "the text", content, Instant.now());

        var body = repo.findByCallId(callId).orElseThrow();
        assertThat(body.responseText()).isEqualTo("the text");
        assertThat(body.responseContentJson()).isNotNull();
        assertThat(body.responseContentJson().get(0).path("name").asText()).isEqualTo("x");
    }

    @Test
    void fourArgInsertLeavesContentNull() {
        var req = json.readTree("{\"messages\":[]}");
        repo.insert(callId, req, "t", Instant.now());
        var body = repo.findByCallId(callId).orElseThrow();
        assertThat(body.responseContentJson()).isNull();
    }

    @Test
    void deleteOlderThanRemovesOldOnly() {
        var oldId = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO vistierie.llm_calls
                  (id, tenant_id, purpose, provider, model, endpoint, status)
                VALUES (?, ?, 'p', 'anthropic', 'm', 'complete', 'ok')
                """).params(oldId, tenantId).update();

        var oldTs = Instant.now().minus(10, ChronoUnit.DAYS);
        var node = json.readTree("{}");
        jdbc.sql("""
                INSERT INTO vistierie.llm_call_bodies
                  (call_id, request_json, response_text, created_at)
                VALUES (?, CAST(? AS jsonb), ?, ?)
                """).params(oldId, "{}", null, Timestamp.from(oldTs)).update();

        repo.insert(callId, node, null, Instant.now());

        var cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
        int deleted = repo.deleteOlderThan(cutoff);
        assertThat(deleted).isEqualTo(1);
        assertThat(repo.findByCallId(oldId)).isEmpty();
        assertThat(repo.findByCallId(callId)).isPresent();
    }
}
