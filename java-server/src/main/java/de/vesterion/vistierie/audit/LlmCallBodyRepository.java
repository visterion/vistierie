package de.vesterion.vistierie.audit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class LlmCallBodyRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public LlmCallBodyRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(String callId, JsonNode requestJson, String responseText, Instant createdAt) {
        insert(callId, requestJson, responseText, null, createdAt);
    }

    public void insert(String callId, JsonNode requestJson, String responseText,
                       JsonNode responseContentJson, Instant createdAt) {
        try {
            String requestStr = json.writeValueAsString(requestJson);
            String contentStr = responseContentJson == null ? null : json.writeValueAsString(responseContentJson);
            jdbc.sql("""
                    INSERT INTO vistierie.llm_call_bodies
                      (call_id, request_json, response_text, response_content_json, created_at)
                    VALUES (?, CAST(? AS jsonb), ?, CAST(? AS jsonb), ?)
                    """).params(callId, requestStr, responseText, contentStr, Timestamp.from(createdAt))
                    .update();
        } catch (Exception e) {
            throw new RuntimeException("body insert failed", e);
        }
    }

    public Optional<LlmCallBody> findByCallId(String callId) {
        return jdbc.sql("""
                SELECT call_id, request_json, response_text, response_content_json, created_at
                FROM vistierie.llm_call_bodies WHERE call_id = ?
                """).param(callId).query((rs, rn) -> {
                    try {
                        var reqNode = json.readTree(rs.getString("request_json"));
                        var contentStr = rs.getString("response_content_json");
                        var contentNode = contentStr == null ? null : json.readTree(contentStr);
                        return new LlmCallBody(
                                rs.getString("call_id"),
                                reqNode,
                                rs.getString("response_text"),
                                contentNode,
                                rs.getTimestamp("created_at").toInstant());
                    } catch (Exception e) {
                        throw new RuntimeException("body parse failed", e);
                    }
                }).optional();
    }

    public int deleteOlderThan(Instant cutoff) {
        return jdbc.sql("""
                DELETE FROM vistierie.llm_call_bodies WHERE created_at < ?
                """).param(Timestamp.from(cutoff)).update();
    }
}
