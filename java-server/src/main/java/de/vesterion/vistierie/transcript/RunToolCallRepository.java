package de.vesterion.vistierie.transcript;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RunToolCallRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public RunToolCallRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(RunToolCall c) {
        jdbc.sql("""
                INSERT INTO vistierie.run_tool_calls
                  (id, run_id, tenant_id, llm_call_id, turn_index, tool_use_id,
                   tool_name, tool_type, input_json, output_json, is_error, error_detail)
                VALUES (?,?,?,?,?,?,?,?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?)
                """)
                .params(c.id(), c.runId(), c.tenantId(), c.llmCallId(), c.turnIndex(),
                        c.toolUseId(), c.toolName(), c.toolType(),
                        str(c.input()), str(c.output()), c.isError(), c.errorDetail())
                .update();
    }

    public List<RunToolCall> findByRun(String runId) {
        return jdbc.sql("""
                SELECT id, run_id, tenant_id, llm_call_id, turn_index, tool_use_id,
                       tool_name, tool_type, input_json, output_json, is_error, error_detail, created_at
                FROM vistierie.run_tool_calls WHERE run_id = ? ORDER BY turn_index, created_at, id
                """).param(runId).query(this::map).list();
    }

    public Optional<RunToolCall> findByRunAndToolUseId(String runId, String toolUseId) {
        return jdbc.sql("""
                SELECT id, run_id, tenant_id, llm_call_id, turn_index, tool_use_id,
                       tool_name, tool_type, input_json, output_json, is_error, error_detail, created_at
                FROM vistierie.run_tool_calls WHERE run_id = ? AND tool_use_id = ?
                """).params(runId, toolUseId).query(this::map).optional();
    }

    private RunToolCall map(ResultSet rs, int n) throws SQLException {
        return new RunToolCall(
                rs.getString("id"),
                rs.getString("run_id"),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("llm_call_id"),
                rs.getInt("turn_index"),
                rs.getString("tool_use_id"),
                rs.getString("tool_name"),
                rs.getString("tool_type"),
                tree(rs.getString("input_json")),
                tree(rs.getString("output_json")),
                rs.getBoolean("is_error"),
                rs.getString("error_detail"),
                rs.getTimestamp("created_at").toInstant());
    }

    private String str(JsonNode n) {
        if (n == null) return null;
        try { return json.writeValueAsString(n); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private JsonNode tree(String s) {
        if (s == null) return null;
        try { return json.readTree(s); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
