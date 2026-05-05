package de.vesterion.vistierie.auth;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthFilterTest extends PostgresTestBase {

    static final String ADMIN_PLAIN = "admin-test-token";

    @DynamicPropertySource
    static void adminHash(DynamicPropertyRegistry r) {
        r.add("vistierie.admin.token-hash",
                () -> new BCryptPasswordEncoder().encode(ADMIN_PLAIN));
    }

    @Autowired WebApplicationContext wac;
    @Autowired TenantRepository tenants;
    @Autowired BCryptPasswordEncoder enc;
    @Autowired AuthFilter authFilter;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac)
                .addFilter(authFilter)
                .build();
    }

    @Test void adminEndpointRequiresAdminToken() throws Exception {
        mvc.perform(get("/admin/tenants")).andExpect(status().isUnauthorized());
    }

    @Test void adminEndpointAcceptsAdminToken() throws Exception {
        mvc.perform(get("/admin/tenants").header("Authorization", "Bearer " + ADMIN_PLAIN))
                .andExpect(status().isOk());
    }

    @Test void llmEndpointRequiresTenantToken() throws Exception {
        mvc.perform(get("/llm/complete")).andExpect(status().isUnauthorized());
    }

    @Test void llmEndpointAcceptsTenantToken() throws Exception {
        String uniqueName = "hivemem-" + UUID.randomUUID();
        String plainToken = "token-" + UUID.randomUUID();
        tenants.insert(UUID.randomUUID(), uniqueName, enc.encode(plainToken));
        // GET on a POST endpoint returns 405 once auth passes; that's our success signal.
        mvc.perform(get("/llm/complete").header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isMethodNotAllowed());
    }
}
