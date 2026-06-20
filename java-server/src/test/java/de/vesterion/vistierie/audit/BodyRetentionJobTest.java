package de.vesterion.vistierie.audit;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BodyRetentionJobTest extends PostgresTestBase {

    @Autowired BodyRetentionJob job;
    @Autowired AuditProperties props;
    @Autowired LlmCallBodyRepository bodies;
    @Autowired TenantRepository tenants;
    @Autowired JdbcClient jdbc;

    UUID tenantId;

    @BeforeEach
    void seed() {
        tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tnt-" + tenantId.toString().substring(0, 8), "x");
    }

    private String seedCall(Instant bodyCreatedAt) {
        var callId = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO vistierie.llm_calls
                  (id, tenant_id, purpose, provider, model, endpoint, status)
                VALUES (?, ?, 'p', 'anthropic', 'm', 'complete', 'ok')
                """).params(callId, tenantId).update();
        jdbc.sql("""
                INSERT INTO vistierie.llm_call_bodies
                  (call_id, request_json, response_text, created_at)
                VALUES (?, CAST('{}' AS jsonb), null, ?)
                """).params(callId, Timestamp.from(bodyCreatedAt)).update();
        return callId;
    }

    @Test
    void deletesOldKeepsRecent() {
        // Pin an explicit 7-day window so the test exercises the deletion
        // boundary independently of the shipped default.
        var props7 = new AuditProperties();
        props7.setBodyRetentionDays(7);
        var job7 = new BodyRetentionJob(bodies, props7);

        var oldId   = seedCall(Instant.now().minus(10, ChronoUnit.DAYS));
        var freshId = seedCall(Instant.now().minus(1, ChronoUnit.DAYS));

        job7.cleanup();

        assertThat(bodies.findByCallId(oldId)).isEmpty();
        assertThat(bodies.findByCallId(freshId)).isPresent();
    }

    @Test
    void shippedDefaultRetentionIs30Days() {
        // Dracul needs ~a month of bodies to analyse — lock the shipped default.
        assertThat(props.getBodyRetentionDays()).isEqualTo(30);
    }

    @Test
    void noopWhenRetentionZeroDisabled() {
        var disabledProps = new AuditProperties();
        disabledProps.setBodyRetentionDays(0);
        var disabledJob = new BodyRetentionJob(bodies, disabledProps);

        var ancientId = seedCall(Instant.now().minus(100, ChronoUnit.DAYS));
        disabledJob.cleanup();
        assertThat(bodies.findByCallId(ancientId)).isPresent();
    }
}
