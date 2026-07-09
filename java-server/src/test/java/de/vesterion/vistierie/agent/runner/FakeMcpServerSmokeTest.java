package de.vesterion.vistierie.agent.runner;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPIKE (Task 0) — proves the reusable {@link FakeMcpServer} test double speaks real MCP
 * Streamable HTTP end-to-end. This class is the seed for {@code McpToolDispatcherTest}
 * in Task 4; read the decision note below before extending it.
 *
 * <h3>(a) Chosen approach + rationale</h3>
 * We stand up a REAL MCP SDK server (mcp-core's servlet-based
 * {@code HttpServletStreamableServerTransportProvider}) on a throwaway embedded Tomcat,
 * and connect with the SDK's {@code HttpClientStreamableHttpTransport} — the exact client
 * Task 4's production {@code dispatchMcp} will use. This gives full protocol realism
 * (initialize handshake, {@code tools/list}, {@code tools/call}, {@code isError}) with no
 * hand-rolled JSON-RPC stub.
 *
 * <p>Deliberate deviation from the brief: we do NOT pull Agora's
 * {@code spring-ai-starter-mcp-server-webmvc}. That starter's auto-config would mount an
 * MCP endpoint onto Vistierie's OWN application context for every {@code @SpringBootTest}
 * in the suite (and Vistierie "is not an MCP server"). mcp-core already bundles the
 * servlet server transport, so the fake needs zero extra dependencies and stays fully
 * decoupled from Spring — matching the {@code ToolDispatcherTest} no-Spring-context style.
 *
 * <h3>(b) Injecting a TRANSIENT failure that fails N times then succeeds (Task 4 retry)</h3>
 * Two recipes, pick per how Task 4's retry keys:
 * <ul>
 *   <li><b>Tool-level (proven here):</b> {@code fail_then_succeed} holds a server-side
 *       {@link java.util.concurrent.atomic.AtomicInteger}; the first
 *       {@code failuresBeforeSuccess} calls return {@code isError=true}, then success.
 *       Use this if Task 4 retries on a tool-level error envelope.</li>
 *   <li><b>Transport-level (recommended if retry keys on transport errors, mirroring
 *       Dracul's reconnect-once):</b> add a counting servlet filter to {@link FakeMcpServer}
 *       (same seam as the bearer-auth filter) that returns HTTP 503 for the first N POSTs
 *       to {@code /mcp}, then passes through — forcing the client to reconnect/retry the
 *       transport. Not built now (no retry logic exists to test yet); the filter seam is
 *       already in place, so it is a few lines when Task 4 needs it.</li>
 * </ul>
 *
 * <h3>(c) Recommendation: make ToolDispatcher's MCP client construction injectable</h3>
 * Mirror the existing {@code (RestClient, ExecutorService)} package-private seam. Introduce
 * a narrow functional interface, e.g.
 * {@code interface McpCaller { McpSchema.CallToolResult call(String server, String tool, Map<String,Object> args); } }
 * and pass it into a package-private {@code ToolDispatcher(McpCaller, ExecutorService)}
 * constructor. The production caller builds/caches {@code McpSyncClient}s per server URL
 * (cache-key = server URL + auth) and injects the bearer via {@code httpRequestCustomizer};
 * unit tests pass a stub {@code McpCaller} to exercise cache-key/retry/concurrency logic in
 * isolation, while THIS {@link FakeMcpServer} covers the real-transport integration path.
 */
class FakeMcpServerSmokeTest {

    static final ObjectMapper M = new ObjectMapper();
    static final String TOKEN = "test-token";

    @Test
    void echoRoundTripsAndAlwaysErrorFlagsIsError() {
        try (FakeMcpServer server = new FakeMcpServer(TOKEN, 0);
             McpSyncClient client = connect(server, TOKEN)) {

            client.initialize();

            // The fake advertises its tools over the real MCP protocol.
            var toolNames = client.listTools().tools().stream().map(McpSchema.Tool::name).toList();
            assertThat(toolNames).contains("echo", "always_error", "fail_then_succeed");

            // echo: input arguments round-trip back through tools/call.
            McpSchema.CallToolResult echo = client.callTool(new McpSchema.CallToolRequest(
                    "echo", Map.of("message", "hi", "n", 42)));
            assertThat(echo.isError()).isNotEqualTo(Boolean.TRUE);
            JsonNode echoed = M.readTree(text(echo));
            assertThat(echoed.path("message").asString()).isEqualTo("hi");
            assertThat(echoed.path("n").asInt()).isEqualTo(42);

            // always_error: isError == true.
            McpSchema.CallToolResult err = client.callTool(new McpSchema.CallToolRequest(
                    "always_error", Map.of()));
            assertThat(err.isError()).isTrue();
        }
    }

    @Test
    void bearerAuthIsEnforced() {
        // Wrong token -> the client cannot even complete the initialize handshake.
        try (FakeMcpServer server = new FakeMcpServer(TOKEN, 0);
             McpSyncClient client = connect(server, "wrong-token")) {
            assertThat(catchThrowable(client::initialize)).isNotNull();
        }
    }

    @Test
    void failThenSucceedFlipsAfterConfiguredFailures() {
        // Proves the transient-failure injection recipe: 2 failures, then success.
        try (FakeMcpServer server = new FakeMcpServer(TOKEN, 2);
             McpSyncClient client = connect(server, TOKEN)) {
            client.initialize();

            List<Boolean> errorFlags = List.of(
                    isError(client, "fail_then_succeed"),
                    isError(client, "fail_then_succeed"),
                    isError(client, "fail_then_succeed"));

            assertThat(errorFlags).containsExactly(true, true, false);
        }
    }

    private static McpSyncClient connect(FakeMcpServer server, String token) {
        var transport = HttpClientStreamableHttpTransport.builder(server.baseUrl())
                .endpoint("/mcp")
                .httpRequestCustomizer((builder, method, uri, body, context) ->
                        builder.setHeader("Authorization", "Bearer " + token))
                .build();
        return McpClient.sync(transport).build();
    }

    private static boolean isError(McpSyncClient client, String tool) {
        return Boolean.TRUE.equals(
                client.callTool(new McpSchema.CallToolRequest(tool, Map.of())).isError());
    }

    private static String text(McpSchema.CallToolResult res) {
        assertThat(res.content()).isNotEmpty();
        return ((McpSchema.TextContent) res.content().getFirst()).text();
    }

    private static Throwable catchThrowable(Runnable r) {
        try {
            r.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }
}
