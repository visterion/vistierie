package de.vesterion.vistierie.agents;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

class AgentDefinitionValidatorStreamingTest {

    AgentDefinitionValidator v = new AgentDefinitionValidator(new JsonSchemas());

    @Test
    void nullDurationIsNotStreaming_alwaysPasses() {
        assertThatNoException().isThrownBy(() ->
                v.validateStreaming(null, null, null));
        assertThatNoException().isThrownBy(() ->
                v.validateStreaming(null, null, null));
    }

    @Test
    void durationZeroIsRejected() {
        assertThatThrownBy(() ->
                v.validateStreaming("https://host/events", "0 9 * * * *", 0))
                .isInstanceOf(AgentDefinitionValidator.InvalidDefinitionException.class)
                .hasMessageContaining("session_duration_seconds must be > 0");
    }

    @Test
    void durationNegativeIsRejected() {
        assertThatThrownBy(() ->
                v.validateStreaming("https://host/events", "0 9 * * * *", -1))
                .isInstanceOf(AgentDefinitionValidator.InvalidDefinitionException.class)
                .hasMessageContaining("session_duration_seconds must be > 0");
    }

    @Test
    void missingEventSourceUrlIsRejected() {
        assertThatThrownBy(() ->
                v.validateStreaming(null, "0 9 * * * *", 3600))
                .isInstanceOf(AgentDefinitionValidator.InvalidDefinitionException.class)
                .hasMessageContaining("event_source_url");
    }

    @Test
    void blankEventSourceUrlIsRejected() {
        assertThatThrownBy(() ->
                v.validateStreaming("   ", "0 9 * * * *", 3600))
                .isInstanceOf(AgentDefinitionValidator.InvalidDefinitionException.class)
                .hasMessageContaining("event_source_url");
    }

    @Test
    void missingScheduleIsRejected() {
        assertThatThrownBy(() ->
                v.validateStreaming("https://host/events", null, 3600))
                .isInstanceOf(AgentDefinitionValidator.InvalidDefinitionException.class)
                .hasMessageContaining("schedule");
    }

    @Test
    void blankScheduleIsRejected() {
        assertThatThrownBy(() ->
                v.validateStreaming("https://host/events", "", 3600))
                .isInstanceOf(AgentDefinitionValidator.InvalidDefinitionException.class)
                .hasMessageContaining("schedule");
    }

    @Test
    void validStreamingConfigPasses() {
        assertThatNoException().isThrownBy(() ->
                v.validateStreaming("https://host/events", "0 30 9 * * MON-FRI", 30600));
    }
}
