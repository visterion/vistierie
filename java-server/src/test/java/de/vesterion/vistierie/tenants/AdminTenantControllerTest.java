package de.vesterion.vistierie.tenants;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.auth.AuthFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminTenantControllerTest extends PostgresTestBase {

    static final String ADMIN_PLAIN = "admin-test-token";
    static final String ADMIN_HEADER = "Bearer " + ADMIN_PLAIN;

    @DynamicPropertySource
    static void adminHash(DynamicPropertyRegistry r) {
        r.add("vistierie.admin.token-hash",
                () -> new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(ADMIN_PLAIN));
    }

    @Autowired WebApplicationContext wac;
    @Autowired AuthFilter authFilter;
    @Autowired TenantRepository repo;
    @Autowired tools.jackson.databind.ObjectMapper json;

    MockMvc mvc;

    @BeforeEach void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).addFilter(authFilter).build();
    }

    @Test void createTenantReturnsToken() throws Exception {
        var name = "hivemem-create-" + java.util.UUID.randomUUID();
        var body = mvc.perform(post("/admin/tenants")
                        .header("Authorization", ADMIN_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var node = json.readTree(body);
        assertThat(node.get("name").asText()).isEqualTo(name);
        assertThat(node.get("token").asText()).isNotBlank();
        assertThat(repo.findByName(name)).isPresent();
    }

    @Test void killAndClearKill() throws Exception {
        var name = "draczl-kill-" + java.util.UUID.randomUUID();
        mvc.perform(post("/admin/tenants")
                .header("Authorization", ADMIN_HEADER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/admin/tenants/" + name + "/kill")
                .header("Authorization", ADMIN_HEADER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"freeze\",\"until\":\"2099-01-01T00:00:00Z\",\"setBy\":\"op\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/admin/tenants/" + name + "/kill")
                .header("Authorization", ADMIN_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.until").value("2099-01-01T00:00:00Z"));

        mvc.perform(delete("/admin/tenants/" + name + "/kill")
                .header("Authorization", ADMIN_HEADER))
                .andExpect(status().isNoContent());

        mvc.perform(get("/admin/tenants/" + name + "/kill")
                .header("Authorization", ADMIN_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.until").doesNotExist());
    }
}
