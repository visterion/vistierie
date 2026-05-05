package de.vesterion.vistierie.llm;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.auth.AuthFilter;
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
    @Autowired BCryptPasswordEncoder enc;
    @Autowired JdbcClient jdbc;

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
        jdbc.sql("TRUNCATE vistierie.llm_calls").update();
    }

    @AfterEach void resetWm() { wm.resetAll(); }

    @Test void completeRecordsLlmCall() throws Exception {
        var token = "tok-" + UUID.randomUUID();
        var name = "hivemem-c-" + UUID.randomUUID();
        // routing config matches by tenant *name*. Insert as 'hivemem' so existing routing rules apply.
        var hivemem = tenants.findByName("hivemem").orElse(null);
        if (hivemem == null) {
            tenants.insert(UUID.randomUUID(), "hivemem", enc.encode(token));
        } else {
            // tenant exists from another test - we need a fresh token to authenticate.
            // Update the token_hash on the existing 'hivemem' row by re-inserting (will hit unique violation).
            // Workaround: delete and re-insert.
            jdbc.sql("DELETE FROM vistierie.llm_calls WHERE tenant_id = ?").param(hivemem.id()).update();
            jdbc.sql("DELETE FROM vistierie.tenants WHERE id = ?").param(hivemem.id()).update();
            tenants.insert(UUID.randomUUID(), "hivemem", enc.encode(token));
        }

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
                        {"purpose":"summarize_cell","realm":"privat",
                         "messages":[{"role":"user","content":"hi"}],
                         "max_tokens":256}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("out"))
                .andExpect(jsonPath("$.model").value("claude-haiku-4-5"))
                .andExpect(jsonPath("$.usage.inputTokens").value(10));

        var rows = jdbc.sql("""
                SELECT status, purpose, model, input_tokens
                FROM vistierie.llm_calls
                """).query().listOfRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("status", "ok")
                .containsEntry("purpose", "summarize_cell")
                .containsEntry("model", "claude-haiku-4-5")
                .containsEntry("input_tokens", 10);
    }

    @Test void killSwitchBlocks() throws Exception {
        var token = "tok-k-" + UUID.randomUUID();
        // ensure 'hivemem' tenant exists with a known token
        var existing = tenants.findByName("hivemem").orElse(null);
        if (existing != null) {
            jdbc.sql("DELETE FROM vistierie.llm_calls WHERE tenant_id = ?").param(existing.id()).update();
            jdbc.sql("DELETE FROM vistierie.tenants WHERE id = ?").param(existing.id()).update();
        }
        var id = UUID.randomUUID();
        tenants.insert(id, "hivemem", enc.encode(token));
        tenants.setKill(id, java.time.Instant.parse("2099-01-01T00:00:00Z"), "freeze", "test");

        mvc.perform(post("/llm/complete")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"purpose":"summarize_cell",
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
            jdbc.sql("DELETE FROM vistierie.llm_calls WHERE tenant_id = ?").param(existing.id()).update();
            jdbc.sql("DELETE FROM vistierie.tenants WHERE id = ?").param(existing.id()).update();
        }
        tenants.insert(UUID.randomUUID(), "hivemem", enc.encode(token));

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
                        {"purpose":"vision_attachment",
                         "image":{"type":"base64","media_type":"image/png","data":"AAAA"},
                         "prompt":"describe","max_tokens":256}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("a square"));

        verify(postRequestedFor(urlEqualTo("/v1/messages"))
                .withRequestBody(containing("\"type\":\"image\"")));
    }
}
