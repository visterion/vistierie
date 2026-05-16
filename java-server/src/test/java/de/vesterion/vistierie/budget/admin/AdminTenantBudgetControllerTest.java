package de.vesterion.vistierie.budget.admin;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminTenantBudgetControllerTest extends PostgresTestBase {

    static final String ADMIN_PLAIN = "admin-budget-token";
    static final String ADMIN_HEADER = "Bearer " + ADMIN_PLAIN;

    @DynamicPropertySource
    static void adminHash(DynamicPropertyRegistry r) {
        r.add("vistierie.admin.token-hash",
                () -> new BCryptPasswordEncoder().encode(ADMIN_PLAIN));
    }

    @Autowired WebApplicationContext wac;
    @Autowired AuthFilter authFilter;
    @Autowired TenantRepository tenants;
    @Autowired ObjectMapper json;

    MockMvc mvc;
    String tenantName;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).addFilter(authFilter).build();
        tenantName = "tenant-" + UUID.randomUUID().toString().substring(0, 8);
        tenants.insert(UUID.randomUUID(), tenantName, "tok");
    }

    @Test
    void patchAndGetTenantBudget() throws Exception {
        var patchBody = """
                {
                  "daily_cap_micros": 1000,
                  "monthly_cap_micros": 5000,
                  "daily_warn_percent": 80,
                  "monthly_warn_percent": 90
                }
                """;

        var patched = mvc.perform(patch("/admin/tenants/" + tenantName + "/budget")
                        .header("Authorization", ADMIN_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var patchedNode = json.readTree(patched);
        assertThat(patchedNode.path("daily_cap_micros").asLong()).isEqualTo(1000L);
        assertThat(patchedNode.path("monthly_cap_micros").asLong()).isEqualTo(5000L);
        assertThat(patchedNode.path("daily_usage_micros").asLong()).isZero();
        assertThat(patchedNode.path("daily_remaining_micros").asLong()).isEqualTo(1000L);
        assertThat(patchedNode.path("daily_blocked").asBoolean()).isFalse();

        var fetched = mvc.perform(get("/admin/tenants/" + tenantName + "/budget")
                        .header("Authorization", ADMIN_HEADER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var fetchedNode = json.readTree(fetched);
        assertThat(fetchedNode.path("monthly_warn_percent").asInt()).isEqualTo(90);
        assertThat(fetchedNode.path("monthly_remaining_micros").asLong()).isEqualTo(5000L);
    }

    @Test
    void patchPreservesOmittedTenantBudgetFields() throws Exception {
        mvc.perform(patch("/admin/tenants/" + tenantName + "/budget")
                        .header("Authorization", ADMIN_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "daily_cap_micros": 1000,
                                  "monthly_cap_micros": 5000,
                                  "daily_warn_percent": 80,
                                  "monthly_warn_percent": 90
                                }
                                """))
                .andExpect(status().isOk());

        var fetched = mvc.perform(patch("/admin/tenants/" + tenantName + "/budget")
                        .header("Authorization", ADMIN_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "monthly_cap_micros": 6000
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var node = json.readTree(fetched);
        assertThat(node.path("daily_cap_micros").asLong()).isEqualTo(1000L);
        assertThat(node.path("monthly_cap_micros").asLong()).isEqualTo(6000L);
        assertThat(node.path("daily_warn_percent").asInt()).isEqualTo(80);
        assertThat(node.path("monthly_warn_percent").asInt()).isEqualTo(90);
    }
}
