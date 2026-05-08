package de.vesterion.vistierie.agents;

import de.vesterion.vistierie.agents.dto.ToolDef;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentDefinitionValidatorTest {
    static final ObjectMapper M = new ObjectMapper();
    AgentDefinitionValidator v = new AgentDefinitionValidator(new JsonSchemas());

    JsonNode schema(String json) { try { return M.readTree(json); } catch (Exception e) { throw new RuntimeException(e); } }

    @Test void rejectsBadName() {
        assertThatThrownBy(() -> v.validateName("Bad Name!"))
                .isInstanceOf(AgentDefinitionValidator.InvalidDefinitionException.class)
                .hasMessageContaining("name");
    }

    @Test void acceptsGoodName() {
        v.validateName("bee-isolation-1");
    }

    @Test void toolMustHaveWebhookOrSubagent() {
        var t = new ToolDef("bad", "desc", schema("{\"type\":\"object\"}"),
                null, null, null, null);
        assertThatThrownBy(() -> v.validateTool(t, List.of()))
                .isInstanceOf(AgentDefinitionValidator.InvalidDefinitionException.class)
                .hasMessageContaining("must have either webhook_url or type=subagent");
    }

    @Test void toolCannotHaveBoth() {
        var t = new ToolDef("bad", "desc", schema("{\"type\":\"object\"}"),
                "subagent", "x", "http://x", 30);
        assertThatThrownBy(() -> v.validateTool(t, List.of()))
                .isInstanceOf(AgentDefinitionValidator.InvalidDefinitionException.class);
    }

    @Test void subagentTargetMustExistInTenant() {
        var t = new ToolDef("dispatch", "desc", schema("{\"type\":\"object\"}"),
                "subagent", "ghost", null, null);
        assertThatThrownBy(() -> v.validateTool(t, List.of("real")))
                .isInstanceOf(AgentDefinitionValidator.InvalidDefinitionException.class)
                .hasMessageContaining("target_agent");
    }

    @Test void inputSchemaMustBeValidJsonSchema() {
        var t = new ToolDef("x", "desc", schema("{\"type\":\"banana\"}"),
                null, null, "http://x", 30);
        assertThatThrownBy(() -> v.validateTool(t, List.of()))
                .isInstanceOf(AgentDefinitionValidator.InvalidDefinitionException.class)
                .hasMessageContaining("input_schema");
    }

    @Test void webhookUrlMustBeHttpOrHttps() {
        var t = new ToolDef("x", "desc", schema("{\"type\":\"object\"}"),
                null, null, "file:///etc/passwd", 30);
        assertThatThrownBy(() -> v.validateTool(t, List.of()))
                .isInstanceOf(AgentDefinitionValidator.InvalidDefinitionException.class)
                .hasMessageContaining("webhook_url");
    }

    @Test void validateScheduleAcceptsNullAndBlank() {
        v.validateSchedule(null);
        v.validateSchedule("");
        v.validateSchedule("   ");
    }

    @Test void validateScheduleAcceptsValidSpringCron() {
        v.validateSchedule("0 0 0 * * *");      // daily at midnight
        v.validateSchedule("*/30 * * * * *");   // every 30 seconds
    }

    @Test void validateScheduleRejectsInvalid() {
        assertThatThrownBy(() -> v.validateSchedule("not-a-cron"))
                .isInstanceOf(AgentDefinitionValidator.InvalidDefinitionException.class)
                .hasMessageContaining("schedule");
        assertThatThrownBy(() -> v.validateSchedule("* * * *"))   // 4 fields, Spring wants 6
                .isInstanceOf(AgentDefinitionValidator.InvalidDefinitionException.class);
    }
}
