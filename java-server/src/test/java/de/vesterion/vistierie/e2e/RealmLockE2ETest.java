package de.vesterion.vistierie.e2e;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.auth.AuthFilter;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RealmLockE2ETest extends PostgresTestBase {

    static final String ADMIN = "admin-e2e";
    static final String TENANT_TOKEN = "hivemem-e2e-token";

    @DynamicPropertySource
    static void p(DynamicPropertyRegistry r) {
        r.add("vistierie.admin.token-hash",
                () -> new BCryptPasswordEncoder().encode(ADMIN));
        // Force the MockProvider so we don't hit real Anthropic.
        r.add("vistierie.mock-llm", () -> "true");
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
        tenantName = "hivemem-e2e-" + tenantId.toString().substring(0, 8);
        tenants.insert(tenantId, tenantName,
                new BCryptPasswordEncoder().encode(TENANT_TOKEN));
    }

    @Test
    void medicalRealmIsLockedToConfiguredModel() throws Exception {
        // 1. Admin creates the realm lock.
        //    locked=true + allow_override=false means the model field in the request is ignored.
        //    Using claude-haiku-4-5 as the locked model (known to PriceTable).
        var ruleBody = """
                { "tenant": "%s", "realm": "medical", "purpose": null,
                  "provider": "anthropic", "model": "claude-haiku-4-5",
                  "priority": 10, "allow_override": false, "locked": true }
                """.formatted(tenantName);
        mvc.perform(post("/admin/routing-rules")
                        .header("Authorization", "Bearer " + ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(ruleBody))
                .andExpect(status().isCreated());

        // Wildcard default so resolver can fall through if realm doesn't match.
        var defaultBody = """
                { "tenant": "%s", "realm": null, "purpose": null,
                  "provider": "anthropic", "model": "claude-sonnet-4-6",
                  "priority": 1000, "allow_override": false, "locked": false }
                """.formatted(tenantName);
        mvc.perform(post("/admin/routing-rules")
                        .header("Authorization", "Bearer " + ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(defaultBody))
                .andExpect(status().isCreated());

        // 2. Tenant calls /llm/complete with realm=medical and an override model.
        //    The override (claude-opus-4-7) should be silently ignored because locked=true.
        var callBody = """
                { "realm": "medical", "purpose": "summarize_cell",
                  "model": "claude-opus-4-7",
                  "messages": [{"role":"user","content":"x"}],
                  "max_tokens": 16 }
                """;
        mvc.perform(post("/llm/complete")
                        .header("Authorization", "Bearer " + TENANT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(callBody))
                .andExpect(status().isOk());

        // 3. Assert the latest llm_calls row for this tenant shows the locked model,
        //    NOT the request's override.
        String actualModel = jdbc.sql("""
                SELECT model FROM vistierie.llm_calls
                 WHERE tenant_id = ?
                 ORDER BY created_at DESC LIMIT 1
                """).param(tenantId).query(String.class).single();
        assertThat(actualModel).isEqualTo("claude-haiku-4-5");
        assertThat(actualModel).isNotEqualTo("claude-opus-4-7");
    }

    @Test
    void nonMedicalRealmFallsThroughToDefault() throws Exception {
        var lockBody = """
                { "tenant": "%s", "realm": "medical", "purpose": null,
                  "provider": "anthropic", "model": "claude-haiku-4-5",
                  "priority": 10, "allow_override": false, "locked": true }
                """.formatted(tenantName);
        mvc.perform(post("/admin/routing-rules")
                        .header("Authorization", "Bearer " + ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(lockBody))
                .andExpect(status().isCreated());

        var defaultBody = """
                { "tenant": "%s", "realm": null, "purpose": null,
                  "provider": "anthropic", "model": "claude-sonnet-4-6",
                  "priority": 1000, "allow_override": false, "locked": false }
                """.formatted(tenantName);
        mvc.perform(post("/admin/routing-rules")
                        .header("Authorization", "Bearer " + ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(defaultBody))
                .andExpect(status().isCreated());

        var callBody = """
                { "realm": "privat", "purpose": "summarize_cell",
                  "messages": [{"role":"user","content":"x"}],
                  "max_tokens": 16 }
                """;
        mvc.perform(post("/llm/complete")
                        .header("Authorization", "Bearer " + TENANT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(callBody))
                .andExpect(status().isOk());

        String actualModel = jdbc.sql("""
                SELECT model FROM vistierie.llm_calls
                 WHERE tenant_id = ?
                 ORDER BY created_at DESC LIMIT 1
                """).param(tenantId).query(String.class).single();
        assertThat(actualModel).isEqualTo("claude-sonnet-4-6");
    }
}
