package de.vesterion.vistierie.provider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Verifies OpenAiCompatibleProvidersConfig actually applies the configured
 * vistierie.providers.<name>.timeout-seconds, so a stalled upstream fails fast
 * instead of hanging the calling thread forever (see LlmService fallback path).
 */
class OpenAiCompatibleProvidersConfigTimeoutTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OpenAiCompatibleProvidersConfig.class);

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
        runner.withPropertyValues(
                "vistierie.providers.stalled.base-url=http://localhost:" + serverSocket.getLocalPort(),
                "vistierie.providers.stalled.api-key=test-key",
                "vistierie.providers.stalled.timeout-seconds=1",
                "vistierie.mock-llm=false"
        ).run(ctx -> {
            var beans = ctx.getBeansOfType(OpenAiCompatibleProvider.class);
            assertThat(beans).hasSize(1);
            var provider = beans.values().iterator().next();
            var req = new ProviderRequest("m", 10, null, null,
                    List.of(Map.of("role", "user", "content", "hi")), null, null, null);

            assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                    assertThrows(LlmProvider.ProviderException.class, () -> provider.complete(req)));
        });
    }
}
