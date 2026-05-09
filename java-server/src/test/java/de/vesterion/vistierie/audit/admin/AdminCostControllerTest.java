package de.vesterion.vistierie.audit.admin;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.auth.AuthFilter;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminCostControllerTest extends PostgresTestBase {

    static final String ADMIN = "admin-cost";

    @DynamicPropertySource
    static void p(DynamicPropertyRegistry r) {
        r.add("vistierie.admin.token-hash",
                () -> new BCryptPasswordEncoder().encode(ADMIN));
    }

    @Autowired WebApplicationContext wac;
    @Autowired AuthFilter authFilter;
    @Autowired TenantRepository tenants;
    @Autowired JdbcClient jdbc;

    MockMvc mvc;
    UUID tenantId;
    String tenantName;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).addFilter(authFilter).build();
        tenantId = UUID.randomUUID();
        tenantName = "cost-" + tenantId.toString().substring(0,8);
        tenants.insert(tenantId, tenantName, "x");
    }

    private void seedCall(long costMicros, Instant createdAt, String model) {
        jdbc.sql("""
                INSERT INTO vistierie.llm_calls
                  (id, tenant_id, purpose, provider, model, endpoint, status, cost_micros, created_at)
                VALUES (?, ?, 'p', 'anthropic', ?, 'complete', 'ok', ?, ?)
                """).params(UUID.randomUUID().toString(), tenantId, model, costMicros,
                            Timestamp.from(createdAt)).update();
    }

    @Test
    void costQueryReturnsBuckets() throws Exception {
        var t0 = Instant.now().minus(2, ChronoUnit.HOURS);
        seedCall(100, t0.plus(5, ChronoUnit.MINUTES), "haiku");
        seedCall(200, t0.plus(70, ChronoUnit.MINUTES), "haiku");

        mvc.perform(get("/admin/cost?granularity=hour&group_by=tenant&tenant=" + tenantName)
                        .header("Authorization", "Bearer " + ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granularity").value("hour"))
                .andExpect(jsonPath("$.buckets.length()").value(2));
    }

    @Test
    void granularityNoneFlat() throws Exception {
        var t0 = Instant.now().minus(1, ChronoUnit.HOURS);
        seedCall(100, t0, "haiku");
        seedCall(200, t0.plus(20, ChronoUnit.MINUTES), "haiku");

        mvc.perform(get("/admin/cost?granularity=none&group_by=model&tenant=" + tenantName)
                        .header("Authorization", "Bearer " + ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buckets.length()").value(1))
                .andExpect(jsonPath("$.buckets[0].groups[0].cost_micros").value(300))
                .andExpect(jsonPath("$.buckets[0].groups[0].cost_eur").value(0.0003));
    }

    @Test
    void badGranularityReturns400() throws Exception {
        mvc.perform(get("/admin/cost?granularity=minute")
                        .header("Authorization", "Bearer " + ADMIN))
                .andExpect(status().isBadRequest());
    }

    @Test
    void noBearerReturns401() throws Exception {
        mvc.perform(get("/admin/cost"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tenantTokenReturns401() throws Exception {
        mvc.perform(get("/admin/cost").header("Authorization", "Bearer tenant"))
                .andExpect(status().isUnauthorized());
    }
}
