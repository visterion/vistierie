package de.vesterion.vistierie.routing.admin;

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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminRoutingRuleControllerTest extends PostgresTestBase {

    static final String ADMIN = "admin-test";
    static final String ADMIN_HEADER = "Bearer " + ADMIN;

    @DynamicPropertySource
    static void adminHash(DynamicPropertyRegistry r) {
        r.add("vistierie.admin.token-hash",
                () -> new BCryptPasswordEncoder().encode(ADMIN));
    }

    @Autowired WebApplicationContext wac;
    @Autowired AuthFilter authFilter;
    @Autowired TenantRepository tenants;
    @Autowired tools.jackson.databind.ObjectMapper json;

    MockMvc mvc;
    String tenantName;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).addFilter(authFilter).build();
        tenantName = "t-" + UUID.randomUUID().toString().substring(0, 8);
        tenants.insert(UUID.randomUUID(), tenantName,
                new BCryptPasswordEncoder().encode("tk"));
    }

    @Test
    void postCreatesAndGetReturns() throws Exception {
        var body = """
                { "tenant": "%s", "realm": "medical", "purpose": null,
                  "provider": "anthropic", "model": "x", "effort": "low", "priority": 10,
                  "allow_override": false, "locked": true }
                """.formatted(tenantName);

        var loc = mvc.perform(post("/admin/routing-rules")
                        .header("Authorization", ADMIN_HEADER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.effort").value("low"))
                .andReturn().getResponse().getContentAsString();
        var id = json.readTree(loc).get("id").asText();

        mvc.perform(get("/admin/routing-rules/" + id).header("Authorization", ADMIN_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.realm").value("medical"));
    }

    @Test
    void duplicateReturns409() throws Exception {
        var body = """
                { "tenant": "%s", "realm": "r", "purpose": "p",
                  "provider": "anthropic", "model": "x", "priority": 100,
                  "allow_override": false, "locked": false }
                """.formatted(tenantName);
        mvc.perform(post("/admin/routing-rules")
                        .header("Authorization", ADMIN_HEADER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/admin/routing-rules")
                        .header("Authorization", ADMIN_HEADER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void unknownProviderReturns400() throws Exception {
        var body = """
                { "tenant": "%s", "realm": null, "purpose": null,
                  "provider": "bogus-provider", "model": "x", "priority": 100,
                  "allow_override": false, "locked": false }
                """.formatted(tenantName);
        mvc.perform(post("/admin/routing-rules")
                        .header("Authorization", ADMIN_HEADER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteOfLastWildcardReturns422() throws Exception {
        var body = """
                { "tenant": "%s", "realm": null, "purpose": null,
                  "provider": "anthropic", "model": "x", "priority": 1000,
                  "allow_override": false, "locked": false }
                """.formatted(tenantName);
        var resp = mvc.perform(post("/admin/routing-rules")
                        .header("Authorization", ADMIN_HEADER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var id = json.readTree(resp).get("id").asText();

        mvc.perform(delete("/admin/routing-rules/" + id).header("Authorization", ADMIN_HEADER))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void patchUpdatesPartial() throws Exception {
        var body = """
                { "tenant": "%s", "realm": null, "purpose": "p",
                  "provider": "anthropic", "model": "x", "priority": 500,
                  "allow_override": false, "locked": false }
                """.formatted(tenantName);
        var resp = mvc.perform(post("/admin/routing-rules")
                        .header("Authorization", ADMIN_HEADER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        var id = json.readTree(resp).get("id").asText();

        mvc.perform(patch("/admin/routing-rules/" + id)
                        .header("Authorization", ADMIN_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"model\": \"new-model\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("new-model"))
                .andExpect(jsonPath("$.priority").value(500));
    }

    @Test
    void noBearerReturns401() throws Exception {
        mvc.perform(get("/admin/routing-rules"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tenantTokenOnAdminReturns401() throws Exception {
        mvc.perform(get("/admin/routing-rules").header("Authorization", "Bearer tk"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownTenantReturns400() throws Exception {
        var body = """
                { "tenant": "bogus-tnt", "realm": null, "purpose": null,
                  "provider": "anthropic", "model": "x", "priority": 1000,
                  "allow_override": false, "locked": false }
                """;
        mvc.perform(post("/admin/routing-rules")
                        .header("Authorization", ADMIN_HEADER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void priorityOutOfRangeReturns400() throws Exception {
        var body = """
                { "tenant": "%s", "realm": null, "purpose": null,
                  "provider": "anthropic", "model": "x", "priority": 99999,
                  "allow_override": false, "locked": false }
                """.formatted(tenantName);
        mvc.perform(post("/admin/routing-rules")
                        .header("Authorization", ADMIN_HEADER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listFilterByTenant() throws Exception {
        // Create one rule under our tenant
        var body = """
                { "tenant": "%s", "realm": null, "purpose": "p1",
                  "provider": "anthropic", "model": "x", "priority": 500,
                  "allow_override": false, "locked": false }
                """.formatted(tenantName);
        mvc.perform(post("/admin/routing-rules")
                        .header("Authorization", ADMIN_HEADER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mvc.perform(get("/admin/routing-rules?tenant=" + tenantName)
                        .header("Authorization", ADMIN_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].provider").value("anthropic"));
    }
}
