package de.vesterion.vistierie.runs;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RunRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public RunRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc; this.mapper = mapper;
    }

    /** Insert without session_id (backward-compatible overload). */
    public void insert(String id, UUID tenantId, UUID agentId,
                       JsonNode agentSnapshot, int agentVersion,
                       String parentRunId, String trigger, String status,
                       JsonNode payload,
                       String completionWebhook, String completionWebhookToken) {
        insert(id, tenantId, agentId, agentSnapshot, agentVersion,
                parentRunId, trigger, status, payload,
                completionWebhook, completionWebhookToken, null);
    }

    /** Full insert with optional session_id. */
    public void insert(String id, UUID tenantId, UUID agentId,
                       JsonNode agentSnapshot, int agentVersion,
                       String parentRunId, String trigger, String status,
                       JsonNode payload,
                       String completionWebhook, String completionWebhookToken,
                       UUID sessionId) {
        jdbc.sql("""
                INSERT INTO vistierie.runs
                  (id, tenant_id, agent_id, agent_snapshot, agent_version,
                   parent_run_id, trigger, status, payload,
                   messages_snapshot, completion_webhook, completion_webhook_token,
                   session_id)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?::jsonb, '[]'::jsonb, ?, ?, ?)
                """).params(id, tenantId, agentId,
                    json(agentSnapshot), agentVersion,
                    parentRunId, trigger, status, json(payload),
                    completionWebhook, completionWebhookToken, sessionId).update();
    }

    public void markRunning(String id) {
        jdbc.sql("UPDATE vistierie.runs SET status = 'running' WHERE id = ? AND status = 'queued'")
                .param(id).update();
    }

    public void appendMessages(String id, JsonNode fullMessages) {
        jdbc.sql("UPDATE vistierie.runs SET messages_snapshot = ?::jsonb WHERE id = ?")
                .params(json(fullMessages), id).update();
    }

    public void markTerminal(String id, String status, JsonNode output, String error, String summary) {
        jdbc.sql("""
                UPDATE vistierie.runs
                SET status = ?, output = ?::jsonb, error = ?, summary = ?, finished_at = now()
                WHERE id = ? AND status NOT IN ('done','failed')
                """).params(status, json(output), error, summary, id).update();
    }

    public Optional<Run> findById(String id) {
        return jdbc.sql(SELECT_BASE + " WHERE id = ?").param(id).query(this::map).optional();
    }

    public List<Run> findByTenant(UUID tenantId, int limit) {
        return jdbc.sql(SELECT_BASE + " WHERE tenant_id = ? ORDER BY started_at DESC LIMIT ?")
                .params(tenantId, limit).query(this::map).list();
    }

    public List<Run> findByParent(String parentRunId) {
        return jdbc.sql(SELECT_BASE + " WHERE parent_run_id = ?")
                .param(parentRunId).query(this::map).list();
    }

    public boolean hasOpenRun(UUID agentId) {
        return jdbc.sql("""
                SELECT EXISTS(
                    SELECT 1 FROM vistierie.runs
                    WHERE agent_id = ? AND status IN ('queued','running')
                )
                """).param(agentId).query(Boolean.class).single();
    }

    public Optional<String> latestOpenRunId(UUID agentId) {
        return jdbc.sql("""
                SELECT id FROM vistierie.runs
                WHERE agent_id = ? AND status IN ('queued','running')
                -- Prefer running over queued so cron_skipped attaches to the in-progress run.
                ORDER BY CASE status WHEN 'running' THEN 0 ELSE 1 END,
                         started_at DESC NULLS LAST, id DESC
                LIMIT 1
                """).param(agentId).query(String.class).optional();
    }

    private static final String SELECT_BASE = """
            SELECT id, tenant_id, agent_id, agent_snapshot, agent_version,
                   parent_run_id, trigger, status, payload, messages_snapshot,
                   output, summary, error, completion_webhook, completion_webhook_token,
                   started_at, finished_at, anthropic_batch_id, session_id
            FROM vistierie.runs
            """;

    public List<Run> findOpenBatchParents() {
        return jdbc.sql(SELECT_BASE
                + " WHERE anthropic_batch_id IS NOT NULL AND status IN ('queued','running') ORDER BY started_at NULLS LAST")
                .query(this::map).list();
    }

    public void setAnthropicBatchId(String runId, String anthropicBatchId) {
        jdbc.sql("UPDATE vistierie.runs SET anthropic_batch_id = ? WHERE id = ?")
                .params(anthropicBatchId, runId).update();
    }

    private Run map(java.sql.ResultSet rs, int n) throws SQLException {
        return new Run(
                rs.getString("id"),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("agent_id", UUID.class),
                tree(rs.getString("agent_snapshot")),
                rs.getInt("agent_version"),
                rs.getString("parent_run_id"),
                rs.getString("trigger"),
                rs.getString("status"),
                tree(rs.getString("payload")),
                tree(rs.getString("messages_snapshot")),
                tree(rs.getString("output")),
                rs.getString("summary"),
                rs.getString("error"),
                rs.getString("completion_webhook"),
                rs.getString("completion_webhook_token"),
                rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
                rs.getString("anthropic_batch_id"),
                rs.getObject("session_id", UUID.class)
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
