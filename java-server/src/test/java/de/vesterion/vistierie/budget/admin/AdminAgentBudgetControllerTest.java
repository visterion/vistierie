package de.vesterion.vistierie.budget.admin;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
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
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminAgentBudgetControllerTest extends PostgresTestBase {

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
    @Autowired AgentRepository agents;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;

    MockMvc mvc;
    String tenantName;
    String agentName;
    UUID tenantId;
    UUID agentId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).addFilter(authFilter).build();
        tenantId = UUID.randomUUID();
        tenantName = "tenant-" + tenantId.toString().substring(0, 8);
        tenants.insert(tenantId, tenantName, "tok");
        agentId = UUID.randomUUID();
        agentName = "agent-" + agentId.toString().substring(0, 8);
        agents.insert(agentId, tenantId, agentName, "sys", "routine",
                mapper.createArrayNode(), null, 5, 60, "wt", false, null, null, null, null, null, null);
    }

    private void insertCall(long costMicros, Instant createdAt) {
        jdbc.sql("""
                INSERT INTO vistierie.llm_calls
                  (id, tenant_id, agent_id, purpose, provider, model, endpoint, status, cost_micros, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(UUID.randomUUID().toString(), tenantId, agentId, "routine", "anthropic", "haiku", "complete",
                        "ok", costMicros, Timestamp.from(createdAt))
                .update();
    }

    @Test
    void patchAndGetAgentBudget() throws Exception {
        var patchBody = """
                {
                  "daily_cap_micros": 2000,
                  "monthly_cap_micros": 9000,
                  "daily_warn_percent": 75,
                  "monthly_warn_percent": 95
                }
                """;

        var patched = mvc.perform(patch("/admin/tenants/" + tenantName + "/agents/" + agentName + "/budget")
                        .header("Authorization", ADMIN_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var patchedNode = mapper.readTree(patched);
        assertThat(patchedNode.path("daily_cap_micros").asLong()).isEqualTo(2000L);
        assertThat(patchedNode.path("daily_remaining_micros").asLong()).isEqualTo(2000L);
        assertThat(patchedNode.path("daily_warned").asBoolean()).isFalse();

        var fetched = mvc.perform(get("/admin/tenants/" + tenantName + "/agents/" + agentName + "/budget")
                        .header("Authorization", ADMIN_HEADER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var fetchedNode = mapper.readTree(fetched);
        assertThat(fetchedNode.path("monthly_cap_micros").asLong()).isEqualTo(9000L);
        assertThat(fetchedNode.path("monthly_usage_micros").asLong()).isZero();
        assertThat(fetchedNode.path("monthly_blocked").asBoolean()).isFalse();
    }

    @Test
    void patchPreservesOmittedAgentBudgetFields() throws Exception {
        mvc.perform(patch("/admin/tenants/" + tenantName + "/agents/" + agentName + "/budget")
                        .header("Authorization", ADMIN_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "daily_cap_micros": 2000,
                                  "monthly_cap_micros": 9000,
                                  "daily_warn_percent": 75,
                                  "monthly_warn_percent": 95
                                }
                                """))
                .andExpect(status().isOk());

        var fetched = mvc.perform(patch("/admin/tenants/" + tenantName + "/agents/" + agentName + "/budget")
                        .header("Authorization", ADMIN_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "daily_warn_percent": 70
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var node = mapper.readTree(fetched);
        assertThat(node.path("daily_cap_micros").asLong()).isEqualTo(2000L);
        assertThat(node.path("monthly_cap_micros").asLong()).isEqualTo(9000L);
        assertThat(node.path("daily_warn_percent").asInt()).isEqualTo(70);
        assertThat(node.path("monthly_warn_percent").asInt()).isEqualTo(95);
    }

    @Test
    void getComputesAgentUsageAndRemainingBudget() throws Exception {
        insertCall(600L, Instant.now().minusSeconds(60));
        insertCall(200L, Instant.now().minusSeconds(120));

        mvc.perform(patch("/admin/tenants/" + tenantName + "/agents/" + agentName + "/budget")
                        .header("Authorization", ADMIN_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "daily_cap_micros": 1000,
                                  "monthly_cap_micros": 700,
                                  "daily_warn_percent": 75,
                                  "monthly_warn_percent": 90
                                }
                                """))
                .andExpect(status().isOk());

        var fetched = mvc.perform(get("/admin/tenants/" + tenantName + "/agents/" + agentName + "/budget")
                        .header("Authorization", ADMIN_HEADER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var node = mapper.readTree(fetched);
        assertThat(node.path("daily_usage_micros").asLong()).isEqualTo(800L);
        assertThat(node.path("monthly_usage_micros").asLong()).isEqualTo(800L);
        assertThat(node.path("daily_remaining_micros").asLong()).isEqualTo(200L);
        assertThat(node.path("monthly_remaining_micros").isNull()).isTrue();
        assertThat(node.path("daily_warned").asBoolean()).isTrue();
        assertThat(node.path("monthly_warned").asBoolean()).isTrue();
        assertThat(node.path("daily_blocked").asBoolean()).isFalse();
        assertThat(node.path("monthly_blocked").asBoolean()).isTrue();
    }

    @Test
    void patchRejectsOutOfRangeWarnPercent() throws Exception {
        mvc.perform(patch("/admin/tenants/" + tenantName + "/agents/" + agentName + "/budget")
                        .header("Authorization", ADMIN_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "monthly_warn_percent": 0
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchRejectsWrongJsonTypes() throws Exception {
        mvc.perform(patch("/admin/tenants/" + tenantName + "/agents/" + agentName + "/budget")
                        .header("Authorization", ADMIN_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "daily_cap_micros": "oops",
                                  "monthly_warn_percent": false
                                }
                                """))
                .andExpect(status().isBadRequest());
    }
}
