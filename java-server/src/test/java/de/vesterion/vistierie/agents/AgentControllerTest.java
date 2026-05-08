package de.vesterion.vistierie.agents;

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

class AgentControllerTest extends PostgresTestBase {

    @Autowired WebApplicationContext wac;
    @Autowired AuthFilter authFilter;
    @Autowired TenantRepository tenants;
    @Autowired BCryptPasswordEncoder enc;

    MockMvc mvc;
    String token;

    @BeforeEach void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).addFilter(authFilter).build();
        token = "tok-" + UUID.randomUUID();
        var existing = tenants.findByName("hivemem");
        existing.ifPresent(t -> tenants.delete(t.id()));
        tenants.insert(UUID.randomUUID(), "hivemem", enc.encode(token));
    }

    @Test void createListGetUpdateDelete() throws Exception {
        var body = """
                { "name": "bee-isolation",
                  "system_prompt": "you are a bee",
                  "model_purpose": "bee-isolation",
                  "tools": [
                    { "name": "cell.read", "description": "read",
                      "input_schema": { "type": "object", "properties": {"id":{"type":"string"}}, "required":["id"] },
                      "webhook_url": "http://hivemem:8080/tools/cell.read" }
                  ],
                  "output_schema": { "type": "object", "properties": {"tunnels":{"type":"array"}}, "required":["tunnels"] },
                  "max_turns": 10, "max_run_seconds": 120,
                  "webhook_token": "secret" }
                """;
        mvc.perform(post("/agents")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("bee-isolation"))
                .andExpect(jsonPath("$.version").value(1));

        mvc.perform(get("/agents").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("bee-isolation"));

        mvc.perform(get("/agents/bee-isolation").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.system_prompt").value("you are a bee"));

        var patched = "{\"paused\": true}";
        mvc.perform(patch("/agents/bee-isolation")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(patched))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.paused").value(true));

        mvc.perform(delete("/agents/bee-isolation")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test void rejectsCrossTenantSubagentReference() throws Exception {
        var body = """
                { "name": "queen", "system_prompt": "...", "model_purpose": "queen",
                  "tools": [
                    { "name": "dispatch_bee", "description": "go bee",
                      "input_schema": {"type":"object"},
                      "type": "subagent", "target_agent": "bee" }
                  ],
                  "webhook_token": "x" }
                """;
        mvc.perform(post("/agents")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createWithScheduleEchoesIt() throws Exception {
        var body = """
                { "name":"sched-1","system_prompt":"p","model_purpose":"summarize_cell",
                  "tools":[],"webhook_token":"wt","schedule":"0 */5 * * * *" }
                """;
        mvc.perform(post("/agents").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.schedule").value("0 */5 * * * *"));
    }

    @Test
    void createWithInvalidScheduleRejected() throws Exception {
        var body = """
                { "name":"bad-sched","system_prompt":"p","model_purpose":"summarize_cell",
                  "tools":[],"webhook_token":"wt","schedule":"not-a-cron" }
                """;
        mvc.perform(post("/agents").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchSchedule() throws Exception {
        var create = """
                { "name":"sched-2","system_prompt":"p","model_purpose":"summarize_cell",
                  "tools":[],"webhook_token":"wt" }
                """;
        mvc.perform(post("/agents").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(create))
                .andExpect(status().isCreated());

        mvc.perform(patch("/agents/sched-2").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"schedule\":\"0 0 0 * * *\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedule").value("0 0 0 * * *"));

        // Clear via empty string
        mvc.perform(patch("/agents/sched-2").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"schedule\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedule").doesNotExist());
    }

    @Test void deleteBlockedWhenReferenced() throws Exception {
        var beeBody = """
                { "name": "bee", "system_prompt": "...", "model_purpose": "bee",
                  "tools": [],
                  "output_schema": {"type":"object","properties":{"x":{"type":"string"}},"required":["x"]},
                  "webhook_token": "wt" }
                """;
        mvc.perform(post("/agents").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(beeBody))
                .andExpect(status().isCreated());

        var queenBody = """
                { "name": "queen", "system_prompt": "...", "model_purpose": "queen",
                  "tools": [
                    { "name":"dispatch_bee", "description": "go",
                      "input_schema":{"type":"object"},
                      "type":"subagent", "target_agent":"bee" }
                  ],
                  "webhook_token": "wt" }
                """;
        mvc.perform(post("/agents").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(queenBody))
                .andExpect(status().isCreated());

        mvc.perform(delete("/agents/bee").header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }
}
