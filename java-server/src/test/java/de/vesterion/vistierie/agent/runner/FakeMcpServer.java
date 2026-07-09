package de.vesterion.vistierie.agent.runner;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reusable in-process fake MCP server test double. Hosts a REAL MCP SDK server
 * ({@code io.modelcontextprotocol.sdk:mcp-core}) over Streamable HTTP at {@code /mcp},
 * so tests exercise the genuine protocol handshake + {@code tools/call} envelopes — not
 * a hand-rolled JSON-RPC stub.
 *
 * <p>The server transport is the SDK's servlet-based
 * {@link HttpServletStreamableServerTransportProvider}, mounted on a throwaway embedded
 * Tomcat (already on the classpath via {@code spring-boot-starter-web}). Nothing here
 * touches Vistierie's Spring context, mirroring the {@code ToolDispatcherTest} convention
 * of building collaborators directly.
 *
 * <p>Tools exposed:
 * <ul>
 *   <li>{@code echo} — returns its input arguments back as JSON text (non-error).</li>
 *   <li>{@code always_error} — returns a result with {@code isError=true}.</li>
 *   <li>{@code fail_then_succeed} — a server-side counter fails the first
 *       {@code failuresBeforeSuccess} calls (tool-level {@code isError=true}) then
 *       succeeds; the transient-failure injection recipe for Task 4's retry test.</li>
 * </ul>
 *
 * <p>If constructed with a non-null bearer token, a servlet filter enforces
 * {@code Authorization: Bearer <token>} on every request (401 otherwise) so tests can
 * prove the client's {@code httpRequestCustomizer} auth-injection path end-to-end.
 */
public final class FakeMcpServer implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, Object> OBJECT_SCHEMA = Map.of("type", "object");

    // Strong references so JUL does not garbage-collect these loggers and reset their
    // levels between tests (a classic JUL gotcha). The embedded Tomcat that hosts the
    // fake logs startup/shutdown chatter (and ThreadLocal-leak WARNINGs on stop) via
    // JUL; keep test output pristine.
    @SuppressWarnings("unused")
    private static final Logger[] QUIETED_LOGGERS = {
            quiet("org.apache.catalina", Level.WARNING),
            quiet("org.apache.coyote", Level.WARNING),
            quiet("org.apache.tomcat", Level.WARNING),
            quiet("org.apache.catalina.loader.WebappClassLoaderBase", Level.SEVERE),
    };

    private static Logger quiet(String name, Level level) {
        Logger logger = Logger.getLogger(name);
        logger.setLevel(level);
        return logger;
    }

    private final Tomcat tomcat;
    private final McpSyncServer mcpServer;
    private final String baseUrl;
    private final AtomicInteger failThenSucceedCalls = new AtomicInteger();

    /** Start with no auth and no forced failures. */
    public FakeMcpServer() {
        this(null, 0);
    }

    /**
     * @param bearerToken            if non-null, every request must carry
     *                               {@code Authorization: Bearer <bearerToken>}
     * @param failuresBeforeSuccess  number of leading {@code fail_then_succeed} calls
     *                               that return {@code isError=true} before succeeding
     */
    public FakeMcpServer(String bearerToken, int failuresBeforeSuccess) {
        try {
            HttpServletStreamableServerTransportProvider transport =
                    HttpServletStreamableServerTransportProvider.builder()
                            .mcpEndpoint("/mcp")
                            .build();

            this.mcpServer = McpServer.sync(transport)
                    .serverInfo("fake-mcp", "0.0.1")
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                    .tools(echoTool(), alwaysErrorTool(), failThenSucceedTool(failuresBeforeSuccess))
                    .build();

            Path baseDir = Files.createTempDirectory("fake-mcp-tomcat");
            baseDir.toFile().deleteOnExit();

            this.tomcat = new Tomcat();
            this.tomcat.setBaseDir(baseDir.toString());
            this.tomcat.setPort(0);
            this.tomcat.getConnector().setPort(0); // ensure connector is created and ephemeral

            Context ctx = tomcat.addContext("", baseDir.toString());
            Wrapper wrapper = Tomcat.addServlet(ctx, "mcp", transport);
            wrapper.setAsyncSupported(true);
            ctx.addServletMappingDecoded("/mcp", "mcp");

            if (bearerToken != null) {
                addAuthFilter(ctx, bearerToken);
            }

            tomcat.start();
            this.baseUrl = "http://localhost:" + tomcat.getConnector().getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException("failed to start fake MCP server", e);
        }
    }

    /** Base URL (no path); the MCP endpoint is at {@code baseUrl() + "/mcp"}. */
    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public void close() {
        try {
            mcpServer.closeGracefully();
        } catch (Exception ignored) {
            // best effort
        }
        try {
            tomcat.stop();
            tomcat.destroy();
        } catch (Exception ignored) {
            // best effort
        }
    }

    private static SyncToolSpecification echoTool() {
        return SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("echo")
                        .description("Echoes its input arguments back as JSON text.")
                        .inputSchema(OBJECT_SCHEMA)
                        .build())
                .callHandler((exchange, request) -> {
                    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
                    return McpSchema.CallToolResult.builder()
                            .addTextContent(MAPPER.writeValueAsString(args))
                            .isError(false)
                            .build();
                })
                .build();
    }

    private static SyncToolSpecification alwaysErrorTool() {
        return SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("always_error")
                        .description("Always returns a result with isError=true.")
                        .inputSchema(OBJECT_SCHEMA)
                        .build())
                .callHandler((exchange, request) -> McpSchema.CallToolResult.builder()
                        .addTextContent("{\"error\":\"always_error tool deliberately failed\"}")
                        .isError(true)
                        .build())
                .build();
    }

    private SyncToolSpecification failThenSucceedTool(int failuresBeforeSuccess) {
        return SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("fail_then_succeed")
                        .description("Returns isError=true for the first N calls, then succeeds.")
                        .inputSchema(OBJECT_SCHEMA)
                        .build())
                .callHandler((exchange, request) -> {
                    int n = failThenSucceedCalls.incrementAndGet();
                    if (n <= failuresBeforeSuccess) {
                        return McpSchema.CallToolResult.builder()
                                .addTextContent("{\"error\":\"transient failure #" + n + "\"}")
                                .isError(true)
                                .build();
                    }
                    return McpSchema.CallToolResult.builder()
                            .addTextContent("{\"ok\":true,\"attempt\":" + n + "}")
                            .isError(false)
                            .build();
                })
                .build();
    }

    private static void addAuthFilter(Context ctx, String bearerToken) {
        FilterDef def = new FilterDef();
        def.setFilterName("bearer-auth");
        def.setFilter(new BearerAuthFilter(bearerToken));
        def.setAsyncSupported("true");
        ctx.addFilterDef(def);

        FilterMap map = new FilterMap();
        map.setFilterName("bearer-auth");
        map.addURLPattern("/*");
        ctx.addFilterMap(map);
    }

    /** Rejects any request lacking the exact {@code Authorization: Bearer <token>} header. */
    private static final class BearerAuthFilter implements Filter {
        private final String expected;

        BearerAuthFilter(String token) {
            this.expected = "Bearer " + token;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest http = (HttpServletRequest) request;
            if (!expected.equals(http.getHeader("Authorization"))) {
                ((HttpServletResponse) response).sendError(HttpServletResponse.SC_UNAUTHORIZED, "missing/invalid bearer token");
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
