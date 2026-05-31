package de.vesterion.vistierie.e2e;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.auth.AuthFilter;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminApiE2ETest extends PostgresTestBase {

    static final String ADMIN = "admin-e2e";

    @DynamicPropertySource
    static void p(DynamicPropertyRegistry r) {
        r.add("vistierie.admin.token-hash",
                () -> new BCryptPasswordEncoder().encode(ADMIN));
        r.add("vistierie.mock-llm", () -> "true");
    }

    @Autowired WebApplicationContext wac;
    @Autowired AuthFilter authFilter;
    @Autowired TenantRepository tenants;
    @Autowired ObjectMapper mapper;

    MockMvc mvc;
    UUID tenantId;
    String tenantName;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).addFilter(authFilter).build();
        tenantId = UUID.randomUUID();
        tenantName = "tn-" + tenantId;
        tenants.insert(tenantId, tenantName,
                new BCryptPasswordEncoder().encode("tok-" + tenantId));
    }

    @Test
    void routingRulesCrud() throws Exception {
        // 1. POST — create realm-specific rule (realm="medical" so it's deletable)
        var createBody = """
                { "tenant": "%s", "realm": "medical", "purpose": null,
                  "provider": "anthropic", "model": "claude-haiku-4-5",
                  "priority": 10, "allow_override": false, "locked": false }
                """.formatted(tenantName);

        var createResult = mvc.perform(post("/admin/routing-rules")
                        .header("Authorization", "Bearer " + ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.realm").value("medical"))
                .andExpect(jsonPath("$.model").value("claude-haiku-4-5"))
                .andExpect(jsonPath("$.priority").value(10))
                .andReturn();

        String id = mapper.readTree(createResult.getResponse().getContentAsString())
                .path("id").asText();

        // 2. GET list — filtered by tenant, must contain our rule
        mvc.perform(get("/admin/routing-rules")
                        .header("Authorization", "Bearer " + ADMIN)
                        .param("tenant", tenantName))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.realm=='medical')]").exists())
                .andExpect(jsonPath("$[?(@.id=='" + id + "')]").exists());

        // 3. GET by ID — fields match
        mvc.perform(get("/admin/routing-rules/" + id)
                        .header("Authorization", "Bearer " + ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.realm").value("medical"))
                .andExpect(jsonPath("$.model").value("claude-haiku-4-5"))
                .andExpect(jsonPath("$.priority").value(10))
                .andExpect(jsonPath("$.allow_override").value(false))
                .andExpect(jsonPath("$.locked").value(false));

        // 4. PATCH — change model and priority
        var patchBody = """
                { "model": "claude-sonnet-4-6", "priority": 20 }
                """;

        mvc.perform(patch("/admin/routing-rules/" + id)
                        .header("Authorization", "Bearer " + ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(patchBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("claude-sonnet-4-6"))
                .andExpect(jsonPath("$.priority").value(20));

        // 5. DELETE — 204
        mvc.perform(delete("/admin/routing-rules/" + id)
                        .header("Authorization", "Bearer " + ADMIN))
                .andExpect(status().isNoContent());

        // Verify gone — GET by ID should return 404
        mvc.perform(get("/admin/routing-rules/" + id)
                        .header("Authorization", "Bearer " + ADMIN))
                .andExpect(status().isNotFound());
    }

    @Test
    void tenantBudgetGetAndPatch() throws Exception {
        // 6. PATCH budget caps and warn thresholds
        var patchBody = """
                { "daily_cap_micros": 500000,
                  "monthly_cap_micros": 5000000,
                  "daily_warn_percent": 75,
                  "monthly_warn_percent": 80 }
                """;

        mvc.perform(patch("/admin/tenants/" + tenantName + "/budget")
                        .header("Authorization", "Bearer " + ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(patchBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daily_cap_micros").value(500000))
                .andExpect(jsonPath("$.monthly_cap_micros").value(5000000))
                .andExpect(jsonPath("$.daily_warn_percent").value(75))
                .andExpect(jsonPath("$.monthly_warn_percent").value(80));

        // 7. GET — returns the caps just set
        mvc.perform(get("/admin/tenants/" + tenantName + "/budget")
                        .header("Authorization", "Bearer " + ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daily_cap_micros").value(500000))
                .andExpect(jsonPath("$.monthly_cap_micros").value(5000000))
                .andExpect(jsonPath("$.daily_warn_percent").value(75))
                .andExpect(jsonPath("$.monthly_warn_percent").value(80));
    }

    @Test
    void tenantCreateAndList() throws Exception {
        String newName = "new-tn-" + UUID.randomUUID().toString().substring(0, 8);

        // 8. POST — create a new tenant
        var createBody = """
                { "name": "%s" }
                """.formatted(newName);

        mvc.perform(post("/admin/tenants")
                        .header("Authorization", "Bearer " + ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value(newName))
                .andExpect(jsonPath("$.token").exists());

        // 9. GET list — both setUp tenant and newly created tenant appear
        mvc.perform(get("/admin/tenants")
                        .header("Authorization", "Bearer " + ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='" + tenantName + "')]").exists())
                .andExpect(jsonPath("$[?(@.name=='" + newName + "')]").exists());
    }
}
