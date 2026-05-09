package de.vesterion.vistierie.audit;

import de.vesterion.vistierie.provider.ProviderRequest;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImageRedactorTest {

    private final ImageRedactor redactor = new ImageRedactor();

    @Test
    void redactsImageBlock() {
        byte[] payload = new byte[1024 * 1024]; // 1 MB
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i & 0xff);
        String b64 = Base64.getEncoder().encodeToString(payload);

        var imageBlock = Map.<String, Object>of(
                "type", "image",
                "source", Map.of("type", "base64", "media_type", "image/png", "data", b64));
        var textBlock = Map.<String, Object>of("type", "text", "text", "describe");
        var msg = Map.<String, Object>of("role", "user", "content", List.of(imageBlock, textBlock));

        var req = new ProviderRequest("claude-haiku-4-5", 16, null, null, List.of(msg), null, null, null);
        var redacted = redactor.redact(req);

        @SuppressWarnings("unchecked")
        var blocks = (List<Map<String, Object>>) redacted.messages().get(0).get("content");
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).get("type")).isEqualTo("image_redacted");
        assertThat(blocks.get(0).get("media_type")).isEqualTo("image/png");
        assertThat(blocks.get(0).get("bytes")).isEqualTo(payload.length);
        assertThat((String) blocks.get(0).get("sha256")).hasSize(64);
        assertThat(blocks.get(0)).doesNotContainKey("data");
        assertThat(blocks.get(1).get("type")).isEqualTo("text");
    }

    @Test
    void leavesTextOnlyMessagesUntouched() {
        var msg = Map.<String, Object>of("role", "user", "content", "hello");
        var req = new ProviderRequest("m", 16, null, "sys", List.of(msg), null, null, null);
        var out = redactor.redact(req);
        assertThat(out.messages().get(0).get("content")).isEqualTo("hello");
        assertThat(out.system()).isEqualTo("sys");
    }

    @Test
    void doesNotMutateOriginal() {
        var imageBlock = Map.<String, Object>of(
                "type", "image",
                "source", Map.of("type", "base64", "media_type", "image/png", "data", "AAAA"));
        var msg = Map.<String, Object>of("role", "user", "content", List.of(imageBlock));
        var req = new ProviderRequest("m", 16, null, null, List.of(msg), null, null, null);

        redactor.redact(req);

        @SuppressWarnings("unchecked")
        var origBlocks = (List<Map<String, Object>>) req.messages().get(0).get("content");
        assertThat(origBlocks.get(0).get("type")).isEqualTo("image");
    }

    @Test
    void handlesMissingMessages() {
        var req = new ProviderRequest("m", 16, null, null, null, null, null, null);
        var out = redactor.redact(req);
        assertThat(out).isSameAs(req);
    }
}
