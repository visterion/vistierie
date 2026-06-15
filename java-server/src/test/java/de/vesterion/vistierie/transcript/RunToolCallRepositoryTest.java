package de.vesterion.vistierie.transcript;

import de.vesterion.vistierie.PostgresTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RunToolCallRepositoryTest extends PostgresTestBase {

    @Autowired RunToolCallRepository repo;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;

    UUID tenantId;
    UUID agentId;
    String runId;

    @BeforeEach
    void seed() {
        tenantId = UUID.randomUUID();
        agentId = UUID.randomUUID();
        jdbc.sql("INSERT INTO vistierie.tenants (id, name, token_hash) VALUES (?,?,?)")
                .params(tenantId, "t-" + tenantId.toString().substring(0, 8), "x").update();
        jdbc.sql("""
                INSERT INTO vistierie.agents (id, tenant_id, name, system_prompt, model_purpose,
                    tools, output_schema, max_turns, max_run_seconds, webhook_token, paused)
                VALUES (?,?,?,?,?,'[]'::jsonb, NULL, 5, 60, 'wt', false)
                """).params(agentId, tenantId, "a", "p", "routine").update();
        runId = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        jdbc.sql("""
                INSERT INTO vistierie.runs (id, tenant_id, agent_id, agent_snapshot, agent_version,
                    trigger, status) VALUES (?,?,?,'{}'::jsonb,1,'manual','running')
                """).params(runId, tenantId, agentId).update();
        // seed minimal llm_call rows so the FK on run_tool_calls.llm_call_id is satisfied
        // ON CONFLICT DO NOTHING because the container is shared across tests
        for (String callId : List.of("CALL0", "CALL1", "C")) {
            jdbc.sql("""
                    INSERT INTO vistierie.llm_calls
                      (id, tenant_id, purpose, provider, model, endpoint, status)
                    VALUES (?,?,'routine','stub','stub-model','complete','ok')
                    ON CONFLICT (id) DO NOTHING
                    """).params(callId, tenantId).update();
        }
    }

    @Test
    void insertsAndReadsBackInTurnOrder() throws Exception {
        repo.insert(new RunToolCall("ID2", runId, tenantId, "CALL1", 1, "toolu_b", "finnhub", "http",
                mapper.readTree("{\"q\":\"x\"}"), mapper.readTree("{\"count\":0}"), false, null, null));
        repo.insert(new RunToolCall("ID1", runId, tenantId, "CALL0", 0, "toolu_a", "edgar", "http",
                mapper.readTree("{}"), null, true, "tool_error: 4xx: 401 bad key", null));

        var rows = repo.findByRun(runId);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).turnIndex()).isZero();
        assertThat(rows.get(0).isError()).isTrue();
        assertThat(rows.get(0).errorDetail()).contains("401");
        assertThat(rows.get(1).toolName()).isEqualTo("finnhub");
        assertThat(rows.get(1).output().path("count").asInt()).isZero();
    }

    @Test
    void findByRunAndToolUseIdReturnsUntruncated() throws Exception {
        repo.insert(new RunToolCall("X", runId, tenantId, "C", 0, "toolu_z", "edgar", "http",
                mapper.readTree("{\"a\":1}"), mapper.readTree("{\"big\":\"value\"}"), false, null, null));
        var found = repo.findByRunAndToolUseId(runId, "toolu_z").orElseThrow();
        assertThat(found.output().path("big").asText()).isEqualTo("value");
        assertThat(repo.findByRunAndToolUseId(runId, "nope")).isEmpty();
    }
}
