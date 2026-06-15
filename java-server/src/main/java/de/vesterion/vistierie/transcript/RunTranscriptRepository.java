package de.vesterion.vistierie.transcript;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Repository
public class RunTranscriptRepository {

    /** One LLM call (= one turn). request_json/response_content_json may be null for old runs. */
    public record CallRow(String callId, String model,
                          int inputTokens, int outputTokens, int cacheCreate, int cacheRead,
                          JsonNode requestJson, String responseText, JsonNode responseContentJson) {}

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public RunTranscriptRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<CallRow> findCallsByRun(String runId) {
        return jdbc.sql("""
                SELECT c.id, c.model, c.input_tokens, c.output_tokens,
                       c.cache_creation_input_tokens, c.cache_read_input_tokens,
                       b.request_json, b.response_text, b.response_content_json
                FROM vistierie.llm_calls c
                LEFT JOIN vistierie.llm_call_bodies b ON b.call_id = c.id
                WHERE c.run_id = ?
                ORDER BY c.created_at, c.id
                """).param(runId).query(this::map).list();
    }

    private CallRow map(ResultSet rs, int n) throws SQLException {
        return new CallRow(
                rs.getString("id"),
                rs.getString("model"),
                rs.getInt("input_tokens"),
                rs.getInt("output_tokens"),
                rs.getInt("cache_creation_input_tokens"),
                rs.getInt("cache_read_input_tokens"),
                tree(rs.getString("request_json")),
                rs.getString("response_text"),
                tree(rs.getString("response_content_json")));
    }

    private JsonNode tree(String s) {
        if (s == null) return null;
        try { return json.readTree(s); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
