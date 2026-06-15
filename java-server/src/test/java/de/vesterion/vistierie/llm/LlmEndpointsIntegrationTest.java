package de.vesterion.vistierie.llm;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.budget.AgentBudgetRepository;
import de.vesterion.vistierie.budget.TenantBudgetRepository;
import de.vesterion.vistierie.budget.admin.dto.BudgetPatchRequest;
import de.vesterion.vistierie.auth.AuthFilter;
import de.vesterion.vistierie.routing.RoutingRule;
import de.vesterion.vistierie.routing.RoutingRuleRepository;
import de.vesterion.vistierie.routing.RoutingResolver;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.junit.jupiter.api.AfterEach;
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

import java.time.Instant;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LlmEndpointsIntegrationTest extends PostgresTestBase {

    static WireMockServer wm;

    @Autowired WebApplicationContext wac;
    @Autowired AuthFilter authFilter;
    @Autowired TenantRepository tenants;
    @Autowired AgentRepository agents;
    @Autowired TenantBudgetRepository tenantBudgets;
    @Autowired AgentBudgetRepository agentBudgets;
    @Autowired BCryptPasswordEncoder enc;
    @Autowired JdbcClient jdbc;
    @Autowired RoutingRuleRepository routingRules;
    @Autowired RoutingResolver routingResolver;

    MockMvc mvc;

    @DynamicPropertySource
    static void anthropic(DynamicPropertyRegistry r) {
        wm = new WireMockServer(0);
        wm.start();
        configureFor("localhost", wm.port());
        r.add("vistierie.anthropic.base-url", () -> "http://localhost:" + wm.port());
        r.add("vistierie.anthropic.api-key", () -> "test-key");
    }

    @BeforeEach void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).addFilter(authFilter).build();
        // wipe llm_calls between tests for clean assertions
        jdbc.sql("TRUNCATE vistierie.run_tool_calls, vistierie.llm_call_bodies, vistierie.llm_calls").update();
    }

    @AfterEach void resetWm() { wm.resetAll(); }

    private void seedRouting(UUID tenantId) {
        var now = Instant.now();
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, null,
                "anthropic", "claude-sonnet-4-6", 1000, false, false, now, now));
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, "summarize_cell",
                "anthropic", "claude-haiku-4-5", 500, false, false, now, now));
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, "vision_attachment",
                "anthropic", "claude-haiku-4-5", 500, false, false, now, now));
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, "vision_diagram",
                "anthropic", "claude-sonnet-4-6", 500, false, false, now, now));
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, "free_pick",
                "anthropic", "claude-sonnet-4-6", 500, true, false, now, now));
        routingResolver.bumpVersion();
    }

    private UUID seedAgent(UUID tenantId, String name) {
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, name, "sys", "summarize_cell",
                new tools.jackson.databind.ObjectMapper().createArrayNode(), null, 5, 60, "wt", false, null, null, null, null, null, null);
        return agentId;
    }

    private UUID resetHivememTenant(String token) {
        var existing = tenants.findByName("hivemem").orElse(null);
        if (existing != null) {
            jdbc.sql("DELETE FROM vistierie.routing_rules WHERE tenant_id = ?").param(existing.id()).update();
            jdbc.sql("DELETE FROM vistierie.llm_calls WHERE tenant_id = ?").param(existing.id()).update();
            jdbc.sql("DELETE FROM vistierie.tenants WHERE id = ?").param(existing.id()).update();
        }
        var tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "hivemem", enc.encode(token));
        return tenantId;
    }

    @Test void completeRecordsLlmCall() throws Exception {
        var token = "tok-" + UUID.randomUUID();
        // routing config matches by tenant *name*. Insert as 'hivemem' so existing routing rules apply.
        var hivemem = tenants.findByName("hivemem").orElse(null);
        UUID hivememId;
        if (hivemem == null) {
            hivememId = UUID.randomUUID();
            tenants.insert(hivememId, "hivemem", enc.encode(token));
        } else {
            // tenant exists from another test - we need a fresh token to authenticate.
            // Update the token_hash on the existing 'hivemem' row by re-inserting (will hit unique violation).
            // Workaround: delete and re-insert.
            jdbc.sql("DELETE FROM vistierie.routing_rules WHERE tenant_id = ?").param(hivemem.id()).update();
            jdbc.sql("DELETE FROM vistierie.llm_calls WHERE tenant_id = ?").param(hivemem.id()).update();
            jdbc.sql("DELETE FROM vistierie.tenants WHERE id = ?").param(hivemem.id()).update();
            hivememId = UUID.randomUUID();
            tenants.insert(hivememId, "hivemem", enc.encode(token));
        }
        seedRouting(hivememId);
        var agentId = seedAgent(hivememId, "writer");
        tenantBudgets.patch(hivememId, new BudgetPatchRequest(10_000L, 100_000L, 80, 90));
        agentBudgets.patch(agentId, new BudgetPatchRequest(5_000L, 50_000L, 80, 90));

        stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/v1/messages")).willReturn(okJson("""
                {"id":"m","type":"message","role":"assistant","model":"claude-haiku-4-5",
                 "content":[{"type":"text","text":"out"}],"stop_reason":"end_turn",
                 "usage":{"input_tokens":10,"output_tokens":3,
                          "cache_creation_input_tokens":0,"cache_read_input_tokens":0}}
                """)));

        mvc.perform(post("/llm/complete")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"agent_name":"writer","purpose":"summarize_cell","realm":"privat",
                         "messages":[{"role":"user","content":"hi"}],
                         "max_tokens":256}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("out"))
                .andExpect(jsonPath("$.model").value("claude-haiku-4-5"))
                .andExpect(jsonPath("$.usage.inputTokens").value(10))
                .andExpect(header().exists("X-Vistierie-Agent-Daily-Budget-Remaining-Micros"))
                .andExpect(header().exists("X-Vistierie-Tenant-Monthly-Budget-Remaining-Micros"));

        var rows = jdbc.sql("""
                SELECT status, purpose, model, input_tokens, agent_id
                FROM vistierie.llm_calls
                """).query().listOfRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("status", "ok")
                .containsEntry("purpose", "summarize_cell")
                .containsEntry("model", "claude-haiku-4-5")
                .containsEntry("input_tokens", 10)
                .containsEntry("agent_id", agentId);
    }

    @Test void killSwitchBlocks() throws Exception {
        var token = "tok-k-" + UUID.randomUUID();
        // ensure 'hivemem' tenant exists with a known token
        var existing = tenants.findByName("hivemem").orElse(null);
        if (existing != null) {
            jdbc.sql("DELETE FROM vistierie.routing_rules WHERE tenant_id = ?").param(existing.id()).update();
            jdbc.sql("DELETE FROM vistierie.llm_calls WHERE tenant_id = ?").param(existing.id()).update();
            jdbc.sql("DELETE FROM vistierie.tenants WHERE id = ?").param(existing.id()).update();
        }
        var id = UUID.randomUUID();
        tenants.insert(id, "hivemem", enc.encode(token));
        seedRouting(id);
        var agentId = seedAgent(id, "writer");
        tenantBudgets.patch(id, new BudgetPatchRequest(10_000L, 100_000L, 80, 90));
        agentBudgets.patch(agentId, new BudgetPatchRequest(5_000L, 50_000L, 80, 90));
        tenants.setKill(id, java.time.Instant.parse("2099-01-01T00:00:00Z"), "freeze", "test");

        mvc.perform(post("/llm/complete")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"agent_name":"writer","purpose":"summarize_cell",
                         "messages":[{"role":"user","content":"hi"}],
                         "max_tokens":10}
                        """))
                .andExpect(status().isForbidden());

        var killedRows = jdbc.sql(
                "SELECT status FROM vistierie.llm_calls WHERE status = 'killed'")
                .query().listOfRows();
        assertThat(killedRows).hasSize(1);
    }

    @Test void visionEndpointRoutesAndRecords() throws Exception {
        var token = "tok-v-" + UUID.randomUUID();
        var existing = tenants.findByName("hivemem").orElse(null);
        if (existing != null) {
            jdbc.sql("DELETE FROM vistierie.routing_rules WHERE tenant_id = ?").param(existing.id()).update();
            jdbc.sql("DELETE FROM vistierie.llm_calls WHERE tenant_id = ?").param(existing.id()).update();
            jdbc.sql("DELETE FROM vistierie.tenants WHERE id = ?").param(existing.id()).update();
        }
        var visionId = UUID.randomUUID();
        tenants.insert(visionId, "hivemem", enc.encode(token));
        seedRouting(visionId);
        var agentId = seedAgent(visionId, "visionary");
        tenantBudgets.patch(visionId, new BudgetPatchRequest(10_000L, 100_000L, 80, 90));
        agentBudgets.patch(agentId, new BudgetPatchRequest(5_000L, 50_000L, 80, 90));

        stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/v1/messages")).willReturn(okJson("""
                {"id":"m","type":"message","role":"assistant","model":"claude-haiku-4-5",
                 "content":[{"type":"text","text":"a square"}],"stop_reason":"end_turn",
                 "usage":{"input_tokens":50,"output_tokens":4,
                          "cache_creation_input_tokens":0,"cache_read_input_tokens":0}}
                """)));

        mvc.perform(post("/llm/vision")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"agent_name":"visionary","purpose":"vision_attachment",
                         "image":{"type":"base64","media_type":"image/png","data":"AAAA"},
                         "prompt":"describe","max_tokens":256}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("a square"));

        verify(postRequestedFor(urlEqualTo("/v1/messages"))
                .withRequestBody(containing("\"type\":\"image\"")));
    }

    @Test void visionMultiEndpointRoutesAndRecords() throws Exception {
        var token = "tok-vm-" + UUID.randomUUID();
        var existing = tenants.findByName("hivemem").orElse(null);
        if (existing != null) {
            jdbc.sql("DELETE FROM vistierie.routing_rules WHERE tenant_id = ?").param(existing.id()).update();
            jdbc.sql("DELETE FROM vistierie.llm_calls WHERE tenant_id = ?").param(existing.id()).update();
            jdbc.sql("DELETE FROM vistierie.tenants WHERE id = ?").param(existing.id()).update();
        }
        var vmId = UUID.randomUUID();
        tenants.insert(vmId, "hivemem", enc.encode(token));
        seedRouting(vmId);
        var agentId = seedAgent(vmId, "visionary");
        tenantBudgets.patch(vmId, new BudgetPatchRequest(10_000L, 100_000L, 80, 90));
        agentBudgets.patch(agentId, new BudgetPatchRequest(5_000L, 50_000L, 80, 90));

        stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/v1/messages")).willReturn(okJson("""
                {"id":"m","type":"message","role":"assistant","model":"claude-haiku-4-5",
                 "content":[{"type":"text","text":"two squares"}],"stop_reason":"end_turn",
                 "usage":{"input_tokens":60,"output_tokens":4,
                          "cache_creation_input_tokens":0,"cache_read_input_tokens":0}}
                """)));

        mvc.perform(post("/llm/vision-multi")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"agent_name":"visionary","purpose":"vision_attachment",
                         "images":[
                           {"type":"base64","media_type":"image/png","data":"AAAA"},
                           {"type":"base64","media_type":"image/png","data":"BBBB"}],
                         "prompt":"describe","max_tokens":256}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("two squares"));

        verify(postRequestedFor(urlEqualTo("/v1/messages"))
                .withRequestBody(containing("\"data\":\"AAAA\""))
                .withRequestBody(containing("\"data\":\"BBBB\"")));

        var rows = jdbc.sql("SELECT endpoint, status FROM vistierie.llm_calls").query().listOfRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("endpoint", "vision-multi").containsEntry("status", "ok");
    }

    @Test void visionMultiRejectsEmptyImages() throws Exception {
        var token = "tok-vme-" + UUID.randomUUID();
        var tenantId = resetHivememTenant(token);
        seedRouting(tenantId);
        tenantBudgets.patch(tenantId, new BudgetPatchRequest(10_000L, 100_000L, 80, 90));

        mvc.perform(post("/llm/vision-multi")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"agent_name":"visionary","purpose":"vision_attachment",
                         "images":[],"prompt":"describe","max_tokens":256}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test void visionMultiRejectsBlankPrompt() throws Exception {
        var token = "tok-vmb-" + UUID.randomUUID();
        var tenantId = resetHivememTenant(token);
        seedRouting(tenantId);
        tenantBudgets.patch(tenantId, new BudgetPatchRequest(10_000L, 100_000L, 80, 90));

        mvc.perform(post("/llm/vision-multi")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"agent_name":"visionary","purpose":"vision_attachment",
                         "images":[
                           {"type":"base64","media_type":"image/png","data":"AAAA"}],
                         "prompt":"","max_tokens":256}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test void completeRejectsMissingAgentName() throws Exception {
        var token = "tok-missing-" + UUID.randomUUID();
        var tenantId = resetHivememTenant(token);
        seedRouting(tenantId);

        mvc.perform(post("/llm/complete")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"purpose":"summarize_cell",
                         "messages":[{"role":"user","content":"hi"}],
                         "max_tokens":256}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test void completeRejectsUnknownAgentName() throws Exception {
        var token = "tok-unknown-" + UUID.randomUUID();
        var tenantId = resetHivememTenant(token);
        seedRouting(tenantId);
        tenantBudgets.patch(tenantId, new BudgetPatchRequest(10_000L, 100_000L, 80, 90));

        mvc.perform(post("/llm/complete")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"agent_name":"ghost","purpose":"summarize_cell",
                         "messages":[{"role":"user","content":"hi"}],
                         "max_tokens":256}
                        """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("budget_missing_agent"));
    }
}
