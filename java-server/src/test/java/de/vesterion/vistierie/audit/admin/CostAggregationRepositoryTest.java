package de.vesterion.vistierie.audit.admin;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CostAggregationRepositoryTest extends PostgresTestBase {

    @Autowired CostAggregationRepository repo;
    @Autowired TenantRepository tenants;
    @Autowired JdbcClient jdbc;

    UUID tA, tB;
    String nameA, nameB;

    @BeforeEach
    void seed() {
        tA = UUID.randomUUID();
        nameA = "agg-a-" + tA.toString().substring(0,8);
        tenants.insert(tA, nameA, "x");
        tB = UUID.randomUUID();
        nameB = "agg-b-" + tB.toString().substring(0,8);
        tenants.insert(tB, nameB, "x");
    }

    private void seedCall(UUID tenantId, String purpose, String realm,
                          String provider, String model, long costMicros,
                          int inputTokens, Instant createdAt) {
        jdbc.sql("""
                INSERT INTO vistierie.llm_calls
                  (id, tenant_id, purpose, realm, provider, model, endpoint,
                   input_tokens, output_tokens, cost_micros, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'complete', ?, 0, ?, 'ok', ?)
                """).params(UUID.randomUUID().toString(), tenantId, purpose, realm,
                            provider, model, inputTokens, costMicros,
                            Timestamp.from(createdAt)).update();
    }

    @Test
    void groupByTenantHourly() {
        var t0 = Instant.parse("2026-05-09T14:00:00Z");
        seedCall(tA, "p", null, "anthropic", "haiku", 100, 50, t0.plus(5, ChronoUnit.MINUTES));
        seedCall(tA, "p", null, "anthropic", "haiku", 200, 100, t0.plus(15, ChronoUnit.MINUTES));
        seedCall(tB, "p", null, "anthropic", "haiku", 300, 150, t0.plus(25, ChronoUnit.MINUTES));

        var q = new CostAggregationRepository.Query(
                t0, t0.plus(1, ChronoUnit.HOURS),
                "hour", List.of("tenant"),
                null, null, null, null, null, null, null);
        var rows = repo.query(q);

        assertThat(rows).hasSize(2);
        var byTenant = rows.stream().collect(java.util.stream.Collectors.toMap(
                r -> r.groupValues().get("tenant"), r -> r));
        assertThat(byTenant.get(nameA).calls()).isEqualTo(2);
        assertThat(byTenant.get(nameA).costMicros()).isEqualTo(300);
        assertThat(byTenant.get(nameB).calls()).isEqualTo(1);
        assertThat(byTenant.get(nameB).costMicros()).isEqualTo(300);
    }

    @Test
    void granularityNoneCollapsesAllBuckets() {
        var t0 = Instant.now().minus(1, ChronoUnit.HOURS);
        seedCall(tA, "p", null, "anthropic", "haiku", 100, 50, t0);
        seedCall(tA, "p", null, "anthropic", "haiku", 200, 100, t0.plus(30, ChronoUnit.MINUTES));

        var q = new CostAggregationRepository.Query(
                t0.minus(1, ChronoUnit.HOURS), Instant.now().plus(1, ChronoUnit.HOURS),
                "none", List.of("tenant"),
                nameA, null, null, null, null, null, null);
        var rows = repo.query(q);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).bucket()).isNull();
        assertThat(rows.get(0).calls()).isEqualTo(2);
        assertThat(rows.get(0).costMicros()).isEqualTo(300);
    }

    @Test
    void filterByTenant() {
        var t0 = Instant.now().minus(1, ChronoUnit.HOURS);
        seedCall(tA, "p", null, "anthropic", "haiku", 100, 50, t0);
        seedCall(tB, "p", null, "anthropic", "haiku", 200, 100, t0);

        var q = new CostAggregationRepository.Query(
                t0.minus(1, ChronoUnit.HOURS), Instant.now().plus(1, ChronoUnit.HOURS),
                "none", List.of(),
                nameA, null, null, null, null, null, null);
        var rows = repo.query(q);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).costMicros()).isEqualTo(100);
    }

    @Test
    void filterByStatusMultiValue() {
        var t0 = Instant.now().minus(1, ChronoUnit.HOURS);
        var idOk = UUID.randomUUID().toString();
        var idErr = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO vistierie.llm_calls (id,tenant_id,purpose,provider,model,endpoint,status,cost_micros,created_at) VALUES (?,?,?,?,?,?,?,?,?)")
                .params(idOk, tA, "p", "anthropic", "m", "complete", "ok", 100L, Timestamp.from(t0)).update();
        jdbc.sql("INSERT INTO vistierie.llm_calls (id,tenant_id,purpose,provider,model,endpoint,status,cost_micros,created_at) VALUES (?,?,?,?,?,?,?,?,?)")
                .params(idErr, tA, "p", "anthropic", "m", "complete", "error", 0L, Timestamp.from(t0)).update();

        var q = new CostAggregationRepository.Query(
                t0.minus(1, ChronoUnit.HOURS), Instant.now().plus(1, ChronoUnit.HOURS),
                "none", List.of(), nameA, null, null, null, null, null,
                List.of("error"));
        var rows = repo.query(q);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).calls()).isEqualTo(1);
        assertThat(rows.get(0).costMicros()).isEqualTo(0);
    }

    @Test
    void rejectsBadGroupBy() {
        var q = new CostAggregationRepository.Query(
                Instant.now().minus(1, ChronoUnit.HOURS), Instant.now(),
                "hour", List.of("tenant; DROP TABLE"),
                null, null, null, null, null, null, null);
        assertThatThrownBy(() -> repo.query(q))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBadGranularity() {
        var q = new CostAggregationRepository.Query(
                Instant.now().minus(1, ChronoUnit.HOURS), Instant.now(),
                "minute", List.of(),
                null, null, null, null, null, null, null);
        assertThatThrownBy(() -> repo.query(q))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
