package de.vesterion.vistierie.provider;

import de.vesterion.vistierie.pricing.Usage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;
import software.amazon.awssdk.services.bedrockruntime.model.ImageFormat;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BedrockProviderTest {

    @Mock BedrockRuntimeClient client;
    BedrockProvider provider;

    @BeforeEach
    void setUp() {
        provider = new BedrockProvider(client);
    }

    @Test
    void completeReturnsTextAndUsage() {
        when(client.converse(any(ConverseRequest.class))).thenReturn(
            ConverseResponse.builder()
                .output(ConverseOutput.builder()
                    .message(Message.builder()
                        .role(ConversationRole.ASSISTANT)
                        .content(ContentBlock.fromText("hi there"))
                        .build())
                    .build())
                .stopReason(StopReason.END_TURN)
                .usage(TokenUsage.builder()
                    .inputTokens(12)
                    .outputTokens(5)
                    .build())
                .build()
        );

        var req = new ProviderRequest(
            "anthropic.claude-3-5-sonnet-20241022-v2:0", 256, 0.2,
            "you are a poet",
            List.of(Map.of("role", "user", "content", "say hi")),
            null, null, null
        );
        var res = provider.complete(req);

        assertThat(res.text()).isEqualTo("hi there");
        assertThat(res.stopReason()).isEqualTo("end_turn");
        assertThat(res.usage()).isEqualTo(new Usage(12, 5, 0, 0));
        assertThat(res.model()).isEqualTo("anthropic.claude-3-5-sonnet-20241022-v2:0");
    }

    @Test
    void completeWithToolsSendsToolConfigAndParsesToolUse() {
        when(client.converse(any(ConverseRequest.class))).thenReturn(
            ConverseResponse.builder()
                .output(ConverseOutput.builder()
                    .message(Message.builder()
                        .role(ConversationRole.ASSISTANT)
                        .content(
                            ContentBlock.fromText("thinking"),
                            ContentBlock.fromToolUse(ToolUseBlock.builder()
                                .toolUseId("toolu_1")
                                .name("cell.read")
                                .input(Document.fromMap(Map.of(
                                    "id", Document.fromString("c1"))))
                                .build()))
                        .build())
                    .build())
                .stopReason(StopReason.TOOL_USE)
                .usage(TokenUsage.builder().inputTokens(10).outputTokens(4).build())
                .build()
        );

        var tools = List.of(Map.<String, Object>of(
            "name", "cell.read",
            "description", "read a cell",
            "input_schema", Map.of(
                "type", "object",
                "properties", Map.of("id", Map.of("type", "string")),
                "required", List.of("id"))
        ));
        var req = new ProviderRequest(
            "amazon.nova-pro-v1:0", 256, null, "system",
            List.of(Map.of("role", "user", "content", "find c1")),
            tools, null, null
        );

        var captor = ArgumentCaptor.forClass(ConverseRequest.class);
        var res = provider.complete(req);
        org.mockito.Mockito.verify(client).converse(captor.capture());
        ConverseRequest sent = captor.getValue();

        assertThat(res.stopReason()).isEqualTo("tool_use");
        assertThat(sent.toolConfig()).isNotNull();
        assertThat(sent.toolConfig().tools()).hasSize(1);
        assertThat(sent.toolConfig().tools().get(0).toolSpec().name()).isEqualTo("cell.read");
        assertThat(sent.toolConfig().tools().get(0).toolSpec().inputSchema()).isNotNull();

        JsonNode blocks = res.contentBlocks();
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(1).path("type").asText()).isEqualTo("tool_use");
        assertThat(blocks.get(1).path("name").asText()).isEqualTo("cell.read");
        assertThat(blocks.get(1).path("id").asText()).isEqualTo("toolu_1");
        assertThat(blocks.get(1).path("input").path("id").asText()).isEqualTo("c1");
    }

    @Test
    void visionBuildsImageBlock() {
        when(client.converse(any(ConverseRequest.class))).thenReturn(
            ConverseResponse.builder()
                .output(ConverseOutput.builder()
                    .message(Message.builder()
                        .role(ConversationRole.ASSISTANT)
                        .content(ContentBlock.fromText("a chart"))
                        .build())
                    .build())
                .stopReason(StopReason.END_TURN)
                .usage(TokenUsage.builder().inputTokens(3).outputTokens(2).build())
                .build()
        );

        // 1×1 transparent PNG
        String base64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
        var captor = ArgumentCaptor.forClass(ConverseRequest.class);

        var res = provider.vision("amazon.nova-pro-v1:0", 100, "image/png", base64, "describe it");

        org.mockito.Mockito.verify(client).converse(captor.capture());
        ConverseRequest sent = captor.getValue();
        Message userMsg = sent.messages().get(0);

        assertThat(userMsg.content()).hasSize(2);
        assertThat(userMsg.content().get(0).type()).isEqualTo(ContentBlock.Type.IMAGE);
        assertThat(userMsg.content().get(0).image().format()).isEqualTo(ImageFormat.PNG);
        assertThat(userMsg.content().get(1).type()).isEqualTo(ContentBlock.Type.TEXT);
        assertThat(userMsg.content().get(1).text()).isEqualTo("describe it");
        assertThat(res.text()).isEqualTo("a chart");
        assertThat(res.model()).isEqualTo("amazon.nova-pro-v1:0");
    }
}
