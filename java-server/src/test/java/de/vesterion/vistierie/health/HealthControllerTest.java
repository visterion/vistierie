package de.vesterion.vistierie.health;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.auth.AuthFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HealthControllerTest extends PostgresTestBase {
    @Autowired WebApplicationContext wac;
    @Autowired AuthFilter authFilter;

    MockMvc mvc;

    @BeforeEach void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).addFilter(authFilter).build();
    }

    @Test void healthzNoAuth() throws Exception {
        mvc.perform(get("/healthz")).andExpect(status().isOk());
    }
    @Test void readyzNoAuth() throws Exception {
        mvc.perform(get("/readyz")).andExpect(status().isOk());
    }
}
