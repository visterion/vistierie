package de.vesterion.vistierie.e2e;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.auth.AuthFilter;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MultiTenantIsolationTest extends PostgresTestBase {

    @Autowired WebApplicationContext wac;
    @Autowired AuthFilter authFilter;
    @Autowired TenantRepository tenants;
    @Autowired BCryptPasswordEncoder enc;

    MockMvc mvc;
    String tokenA, tokenB;

    @BeforeEach void up() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).addFilter(authFilter).build();
        tokenA = "ta-" + UUID.randomUUID();
        tokenB = "tb-" + UUID.randomUUID();
        var idA = UUID.randomUUID();
        var idB = UUID.randomUUID();
        tenants.insert(idA, "tn-a-" + idA, enc.encode(tokenA));
        tenants.insert(idB, "tn-b-" + idB, enc.encode(tokenB));
    }

    @Test void tenantBCannotSeeAagent() throws Exception {
        var body = """
                { "name":"a","system_prompt":"p","model_purpose":"summarize_cell",
                  "tools":[],"webhook_token":"wt"}
                """;
        mvc.perform(post("/agents").header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mvc.perform(get("/agents").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
