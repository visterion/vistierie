package de.vesterion.vistierie.streaming;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class StreamingSessionRepository {

    private final JdbcClient jdbc;

    public StreamingSessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertOpen(UUID id, UUID tenantId, UUID agentId,
                           Instant openedAt, Instant closesAt) {
        jdbc.sql("""
                INSERT INTO vistierie.streaming_sessions
                  (id, tenant_id, agent_id, opened_at, closes_at, status)
                VALUES (?, ?, ?, ?, ?, 'open')
                """)
                .params(id, tenantId, agentId,
                        Timestamp.from(openedAt), Timestamp.from(closesAt))
                .update();
    }

    public Optional<StreamingSession> findOpenByAgent(UUID agentId) {
        return jdbc.sql(SELECT_BASE + " WHERE agent_id = ? AND status = 'open'")
                .param(agentId).query(this::map).optional();
    }

    public List<StreamingSession> findAllOpen() {
        return jdbc.sql(SELECT_BASE + " WHERE status = 'open'")
                .query(this::map).list();
    }

    public void markClosed(UUID id) {
        jdbc.sql("UPDATE vistierie.streaming_sessions SET status = 'closed' WHERE id = ?")
                .param(id).update();
    }

    public void updateLastPoll(UUID id, Instant ts) {
        jdbc.sql("UPDATE vistierie.streaming_sessions SET last_poll_at = ? WHERE id = ?")
                .params(Timestamp.from(ts), id).update();
    }

    public List<StreamingSession> listByAgent(UUID agentId, int limit) {
        return jdbc.sql(SELECT_BASE
                + " WHERE agent_id = ? ORDER BY opened_at DESC LIMIT ?")
                .params(agentId, limit).query(this::map).list();
    }

    private static final String SELECT_BASE = """
            SELECT id, tenant_id, agent_id, opened_at, closes_at,
                   last_poll_at, status, created_at
            FROM vistierie.streaming_sessions
            """;

    private StreamingSession map(ResultSet rs, int n) throws SQLException {
        var lastPoll = rs.getTimestamp("last_poll_at");
        return new StreamingSession(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("agent_id", UUID.class),
                rs.getTimestamp("opened_at").toInstant(),
                rs.getTimestamp("closes_at").toInstant(),
                lastPoll == null ? null : lastPoll.toInstant(),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
