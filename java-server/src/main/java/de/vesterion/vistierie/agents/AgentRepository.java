package de.vesterion.vistierie.agents;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AgentRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public AgentRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void insert(UUID id, UUID tenantId, String name,
                       String systemPrompt, String modelPurpose,
                       JsonNode tools, JsonNode outputSchema,
                       int maxTurns, int maxRunSeconds,
                       String webhookToken, boolean paused) {
        jdbc.sql("""
                INSERT INTO vistierie.agents
                  (id, tenant_id, name, system_prompt, model_purpose,
                   tools, output_schema, max_turns, max_run_seconds,
                   webhook_token, paused)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
                """)
                .params(id, tenantId, name, systemPrompt, modelPurpose,
                        toJsonString(tools), toJsonString(outputSchema),
                        maxTurns, maxRunSeconds, webhookToken, paused)
                .update();
    }

    public void replace(UUID id, String systemPrompt, String modelPurpose,
                        JsonNode tools, JsonNode outputSchema,
                        int maxTurns, int maxRunSeconds,
                        String webhookToken, boolean paused) {
        jdbc.sql("""
                UPDATE vistierie.agents
                SET system_prompt = ?, model_purpose = ?,
                    tools = ?::jsonb, output_schema = ?::jsonb,
                    max_turns = ?, max_run_seconds = ?,
                    webhook_token = ?, paused = ?,
                    version = version + 1, updated_at = now()
                WHERE id = ?
                """)
                .params(systemPrompt, modelPurpose,
                        toJsonString(tools), toJsonString(outputSchema),
                        maxTurns, maxRunSeconds, webhookToken, paused, id)
                .update();
    }

    public void setPaused(UUID id, boolean paused) {
        jdbc.sql("""
                UPDATE vistierie.agents
                SET paused = ?, version = version + 1, updated_at = now()
                WHERE id = ?
                """).params(paused, id).update();
    }

    public int delete(UUID id) {
        return jdbc.sql("DELETE FROM vistierie.agents WHERE id = ?")
                .param(id).update();
    }

    public Optional<Agent> findById(UUID id) {
        return jdbc.sql(SELECT_BASE + " WHERE id = ?")
                .param(id).query(this::map).optional();
    }

    public Optional<Agent> findByName(UUID tenantId, String name) {
        return jdbc.sql(SELECT_BASE + " WHERE tenant_id = ? AND name = ?")
                .params(tenantId, name).query(this::map).optional();
    }

    public List<Agent> findByTenant(UUID tenantId) {
        return jdbc.sql(SELECT_BASE + " WHERE tenant_id = ? ORDER BY name")
                .param(tenantId).query(this::map).list();
    }

    public boolean anyReferencesSubagent(UUID tenantId, String targetName, UUID excludeAgentId) {
        return jdbc.sql("""
                SELECT count(*) FROM vistierie.agents
                WHERE tenant_id = ?
                  AND id <> ?
                  AND EXISTS (
                    SELECT 1 FROM jsonb_array_elements(tools) t
                    WHERE t->>'type' = 'subagent' AND t->>'target_agent' = ?
                  )
                """)
                .params(tenantId, excludeAgentId, targetName)
                .query(Integer.class).single() > 0;
    }

    private static final String SELECT_BASE = """
            SELECT id, tenant_id, name, system_prompt, model_purpose,
                   tools, output_schema, max_turns, max_run_seconds,
                   webhook_token, paused, version, created_at, updated_at
            FROM vistierie.agents
            """;

    private Agent map(java.sql.ResultSet rs, int n) throws SQLException {
        return new Agent(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("name"),
                rs.getString("system_prompt"),
                rs.getString("model_purpose"),
                parseJson(rs.getString("tools")),
                parseJson(rs.getString("output_schema")),
                rs.getInt("max_turns"),
                rs.getInt("max_run_seconds"),
                rs.getString("webhook_token"),
                rs.getBoolean("paused"),
                rs.getInt("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private String toJsonString(JsonNode n) {
        if (n == null) return null;
        try { return mapper.writeValueAsString(n); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private JsonNode parseJson(String s) {
        if (s == null) return null;
        try { return mapper.readTree(s); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
