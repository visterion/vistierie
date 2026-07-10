package de.vesterion.vistierie.provider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Verifies the public (Spring-wired) AnthropicProvider constructor actually applies
 * the configured read timeout, so a stalled upstream fails fast instead of hanging
 * the calling thread forever (see LlmService fallback path).
 */
class AnthropicProviderTimeoutTest {

    private ServerSocket serverSocket;
    private Thread acceptThread;

    @BeforeEach void up() throws IOException {
        serverSocket = new ServerSocket(0);
        acceptThread = new Thread(() -> {
            while (!serverSocket.isClosed()) {
                try {
                    // accept the connection but never write a response — simulates a stalled upstream
                    serverSocket.accept();
                } catch (IOException ignored) {
                    break;
                }
            }
        });
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    @AfterEach void down() throws IOException {
        serverSocket.close();
    }

    @Test void configuredReadTimeoutPreventsIndefiniteHang() {
        var provider = new AnthropicProvider(
                "http://localhost:" + serverSocket.getLocalPort(), "test-key", 1);
        var req = new ProviderRequest("m", 10, null, null,
                List.of(Map.of("role", "user", "content", "hi")), null, null, null);

        assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                assertThrows(LlmProvider.ProviderException.class, () -> provider.complete(req)));
    }
}
