package de.vesterion.vistierie.tenants;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TenantRepository {

    private final JdbcClient jdbc;

    public TenantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, String name, String tokenHash) {
        jdbc.sql("""
                INSERT INTO vistierie.tenants (id, name, token_hash)
                VALUES (?, ?, ?)
                """).params(id, name, tokenHash).update();
    }

    public Optional<Tenant> findByName(String name) {
        return jdbc.sql("""
                SELECT id, name, token_hash, kill_until, kill_reason, kill_set_by, created_at
                FROM vistierie.tenants WHERE name = ?
                """).param(name).query(this::map).optional();
    }

    public Optional<Tenant> findById(UUID id) {
        return jdbc.sql("""
                SELECT id, name, token_hash, kill_until, kill_reason, kill_set_by, created_at
                FROM vistierie.tenants WHERE id = ?
                """).param(id).query(this::map).optional();
    }

    public java.util.List<Tenant> findAll() {
        return jdbc.sql("""
                SELECT id, name, token_hash, kill_until, kill_reason, kill_set_by, created_at
                FROM vistierie.tenants ORDER BY name
                """).query(this::map).list();
    }

    public void setKill(UUID id, Instant until, String reason, String setBy) {
        jdbc.sql("""
                UPDATE vistierie.tenants
                SET kill_until = ?, kill_reason = ?, kill_set_by = ?
                WHERE id = ?
                """).params(Timestamp.from(until), reason, setBy, id).update();
    }

    public void clearKill(UUID id) {
        jdbc.sql("""
                UPDATE vistierie.tenants
                SET kill_until = NULL, kill_reason = NULL, kill_set_by = NULL
                WHERE id = ?
                """).param(id).update();
    }

    public void delete(UUID id) {
        jdbc.sql("DELETE FROM vistierie.tenants WHERE id = ?").param(id).update();
    }

    public void deleteById(UUID id) {
        jdbc.sql("DELETE FROM vistierie.tenants WHERE id = ?").param(id).update();
    }

    private Tenant map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Tenant(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("token_hash"),
                rs.getTimestamp("kill_until") == null ? null : rs.getTimestamp("kill_until").toInstant(),
                rs.getString("kill_reason"),
                rs.getString("kill_set_by"),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
