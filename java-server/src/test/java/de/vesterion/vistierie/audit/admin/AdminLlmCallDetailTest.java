package de.vesterion.vistierie.audit.admin;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.audit.LlmCallBodyRepository;
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
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminLlmCallDetailTest extends PostgresTestBase {

    static final String ADMIN = "admin-detail";

    @DynamicPropertySource
    static void p(DynamicPropertyRegistry r) {
        r.add("vistierie.admin.token-hash",
                () -> new BCryptPasswordEncoder().encode(ADMIN));
    }

    @Autowired WebApplicationContext wac;
    @Autowired AuthFilter authFilter;
    @Autowired TenantRepository tenants;
    @Autowired LlmCallBodyRepository bodies;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    MockMvc mvc;
    UUID tenantId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).addFilter(authFilter).build();
        tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tnt-" + tenantId.toString().substring(0, 8), "x");
    }

    private String seedCall() {
        var id = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO vistierie.llm_calls
                  (id, tenant_id, purpose, provider, model, endpoint, status)
                VALUES (?, ?, 'p', 'anthropic', 'm', 'complete', 'ok')
                """).params(id, tenantId).update();
        return id;
    }

    @Test
    void detailIncludesBodyWhenPresent() throws Exception {
        var id = seedCall();
        var node = json.readTree("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        bodies.insert(id, node, "answer", Instant.now());

        mvc.perform(get("/admin/llm-calls/" + id).header("Authorization", "Bearer " + ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response_text").value("answer"))
                .andExpect(jsonPath("$.body_evicted").value(false))
                .andExpect(jsonPath("$.request_json.messages[0].content").value("hi"));
    }

    @Test
    void detailMarksBodyEvictedWhenMissing() throws Exception {
        var id = seedCall();
        // No body inserted.

        mvc.perform(get("/admin/llm-calls/" + id).header("Authorization", "Bearer " + ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body_evicted").value(true));
        // request_json/response_text are null — Jackson may emit them as null or omit.
        // The body_evicted flag is the canonical signal.
    }

    @Test
    void unknownIdReturns404() throws Exception {
        mvc.perform(get("/admin/llm-calls/does-not-exist")
                        .header("Authorization", "Bearer " + ADMIN))
                .andExpect(status().isNotFound());
    }
}
