package de.vesterion.vistierie.audit;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.pricing.Usage;
import de.vesterion.vistierie.provider.ProviderRequest;
import de.vesterion.vistierie.provider.ProviderResponse;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LlmCallRecorderBodyTest extends PostgresTestBase {

    @Autowired LlmCallRecorder recorder;
    @Autowired LlmCallBodyRepository bodies;
    @Autowired TenantRepository tenants;
    @Autowired JdbcClient jdbc;

    UUID tenantId;
    UUID agentId;

    @BeforeEach
    void seed() {
        tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tnt-" + tenantId.toString().substring(0, 8), "x");
        agentId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO vistierie.agents
                  (id, tenant_id, name, system_prompt, model_purpose, tools, webhook_token, paused)
                VALUES (?, ?, ?, ?, ?, '[]'::jsonb, ?, false)
                """)
                .params(agentId, tenantId, "agent-" + agentId.toString().substring(0, 8),
                        "sys", "summarize", "wt")
                .update();
    }

    private LlmCallRecorder.Row row(String callId) {
        return new LlmCallRecorder.Row(
                callId, tenantId, agentId, "summarize", null,
                "anthropic", "claude-haiku-4-5", "complete",
                10, 5, 0, 0, 12L, 100, "ok", null, null, null);
    }

    @Test
    void migrationAddsBudgetTablesAndLlmCallAgentId() {
        var tables = jdbc.sql("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'vistierie'
                  AND table_name IN ('tenant_budgets', 'agent_budgets')
                ORDER BY table_name
                """).query(String.class).list();
        assertThat(tables).containsExactly("agent_budgets", "tenant_budgets");

        var llmCallColumns = jdbc.sql("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'vistierie'
                  AND table_name = 'llm_calls'
                  AND column_name = 'agent_id'
                """).query(String.class).list();
        assertThat(llmCallColumns).containsExactly("agent_id");

        var indexes = jdbc.sql("""
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'vistierie'
                  AND indexname IN ('llm_calls_agent_time_idx', 'llm_calls_tenant_agent_time_idx')
                ORDER BY indexname
                """).query(String.class).list();
        assertThat(indexes).containsExactly("llm_calls_agent_time_idx", "llm_calls_tenant_agent_time_idx");
    }

    @Test
    void insertsCallAndBodyAtomically() {
        var callId = UUID.randomUUID().toString();
        var req = new ProviderRequest("claude-haiku-4-5", 16, null, "sys",
                List.of(Map.of("role", "user", "content", "hi")),
                null, null, null);
        var res = new ProviderResponse("answer", "end_turn", new Usage(10, 5, 0, 0), "claude-haiku-4-5");

        recorder.insertWithBody(row(callId), req, res);

        var body = bodies.findByCallId(callId).orElseThrow();
        var savedAgentId = jdbc.sql("""
                SELECT agent_id
                FROM vistierie.llm_calls
                WHERE id = ?
                """).param(callId).query(UUID.class).single();
        assertThat(savedAgentId).isEqualTo(agentId);
        assertThat(body.responseText()).isEqualTo("answer");
        assertThat(body.requestJson().get("system").asText()).isEqualTo("sys");
    }

    @Test
    void redactsImagesInBody() {
        var callId = UUID.randomUUID().toString();
        byte[] payload = new byte[256 * 1024];
        String b64 = Base64.getEncoder().encodeToString(payload);
        var imageBlock = Map.<String, Object>of(
                "type", "image",
                "source", Map.of("type", "base64", "media_type", "image/png", "data", b64));
        var msg = Map.<String, Object>of("role", "user", "content", List.of(imageBlock));
        var req = new ProviderRequest("m", 16, null, null, List.of(msg), null, null, null);
        var res = new ProviderResponse("ok", "end_turn", new Usage(0,0,0,0), "m");

        recorder.insertWithBody(row(callId), req, res);

        var body = bodies.findByCallId(callId).orElseThrow();
        var node = body.requestJson();
        var blocks = node.get("messages").get(0).get("content");
        assertThat(blocks.get(0).get("type").asText()).isEqualTo("image_redacted");
        assertThat(blocks.get(0).has("data")).isFalse();
        assertThat(blocks.get(0).get("bytes").asInt()).isEqualTo(payload.length);
    }

    @Test
    void nullResponseStoresNullText() {
        var callId = UUID.randomUUID().toString();
        var req = new ProviderRequest("m", 16, null, null,
                List.of(Map.of("role","user","content","x")), null, null, null);

        recorder.insertWithBody(row(callId), req, null);

        var body = bodies.findByCallId(callId).orElseThrow();
        assertThat(body.responseText()).isNull();
        assertThat(body.requestJson()).isNotNull();
    }

    @Test
    void duplicateCallIdSecondInsertFailsButFirstBodyIntact() {
        var callId = UUID.randomUUID().toString();
        var req = new ProviderRequest("m", 16, null, null,
                List.of(Map.of("role","user","content","x")), null, null, null);

        recorder.insertWithBody(row(callId),
                req,
                new ProviderResponse("a", "end_turn", new Usage(0,0,0,0), "m"));

        try {
            recorder.insertWithBody(row(callId), req, null);
        } catch (Exception expected) { /* ignore — duplicate PK on llm_calls */ }

        assertThat(bodies.findByCallId(callId)).isPresent();
    }
}
