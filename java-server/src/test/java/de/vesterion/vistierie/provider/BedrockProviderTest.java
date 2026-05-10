package de.vesterion.vistierie.provider;

import de.vesterion.vistierie.pricing.Usage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

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
}
