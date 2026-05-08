package de.vesterion.vistierie.runs;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

@Component
public class RunEventRecorder {

    public record Event(long id, String runId, Instant ts, String level, String type, JsonNode payload) {}

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public RunEventRecorder(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc; this.mapper = mapper;
    }

    public void record(String runId, String level, String type, JsonNode payload) {
        jdbc.sql("""
                INSERT INTO vistierie.run_events (run_id, level, type, payload)
                VALUES (?, ?, ?, ?::jsonb)
                """).params(runId, level, type, json(payload)).update();
    }

    public List<Event> byRun(String runId) {
        return jdbc.sql("""
                SELECT id, run_id, ts, level, type, payload
                FROM vistierie.run_events WHERE run_id = ? ORDER BY ts, id
                """).param(runId).query(this::map).list();
    }

    private Event map(java.sql.ResultSet rs, int n) throws SQLException {
        return new Event(
                rs.getLong("id"),
                rs.getString("run_id"),
                rs.getTimestamp("ts").toInstant(),
                rs.getString("level"),
                rs.getString("type"),
                tree(rs.getString("payload"))
        );
    }

    private String json(JsonNode n) {
        if (n == null) return null;
        try { return mapper.writeValueAsString(n); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private JsonNode tree(String s) {
        if (s == null) return null;
        try { return mapper.readTree(s); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
