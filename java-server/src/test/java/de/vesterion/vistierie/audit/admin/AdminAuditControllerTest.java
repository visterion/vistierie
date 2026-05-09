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

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminAuditControllerTest extends PostgresTestBase {

    static final String ADMIN = "admin-audit";

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
        tenantName = "audit-" + tenantId.toString().substring(0, 8);
        tenants.insert(tenantId, tenantName, "x");
    }

    private void insertLlmCall(String purpose, String realm, String provider, String status) {
        jdbc.sql("""
                INSERT INTO vistierie.llm_calls
                  (id, tenant_id, purpose, realm, provider, model, endpoint,
                   input_tokens, output_tokens, cost_micros, duration_ms, status)
                VALUES (?, ?, ?, ?, ?, ?, 'complete', 0, 0, 0, 0, ?)
                """).params(UUID.randomUUID().toString(), tenantId, purpose, realm,
                provider, "x", status).update();
    }

    @Test
    void llmCallsListReturnsItems() throws Exception {
        insertLlmCall("p1", null, "anthropic", "ok");
        insertLlmCall("p2", "medical", "ollama", "ok");

        mvc.perform(get("/admin/llm-calls?tenant=" + tenantName)
                        .header("Authorization", "Bearer " + ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void llmCallsFilterByRealm() throws Exception {
        insertLlmCall("p1", null, "anthropic", "ok");
        insertLlmCall("p2", "medical", "ollama", "ok");

        mvc.perform(get("/admin/llm-calls?tenant=" + tenantName + "&realm=medical")
                        .header("Authorization", "Bearer " + ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].provider").value("ollama"));
    }

    @Test
    void runsListEmptyWorks() throws Exception {
        mvc.perform(get("/admin/runs").header("Authorization", "Bearer " + ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").exists())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void noBearerReturns401() throws Exception {
        mvc.perform(get("/admin/runs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tenantTokenReturns401() throws Exception {
        mvc.perform(get("/admin/runs").header("Authorization", "Bearer tenant"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void llmCallsLimitClamped() throws Exception {
        mvc.perform(get("/admin/llm-calls?limit=9999")
                        .header("Authorization", "Bearer " + ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(200));
    }
}
