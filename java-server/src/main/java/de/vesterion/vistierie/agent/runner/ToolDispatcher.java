package de.vesterion.vistierie.agent.runner;

import de.vesterion.vistierie.agents.dto.ToolDef;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class ToolDispatcher {

    private static final int MCP_MAX_RETRIES = 3; // 4 attempts total

    private final RestClient http;
    private final ExecutorService executor;
    private final McpClientFactory mcpClientFactory;
    private final long mcpRetryBaseMillis;
    private final int defaultTimeoutSeconds;
    private final ConcurrentHashMap<String, McpClientHolder> mcpClients = new ConcurrentHashMap<>();

    @Autowired
    public ToolDispatcher(
            @Value("${vistierie.agents.tool-default-timeout-seconds:30}") int defaultTimeout,
            @Value("${vistierie.agents.mcp-retry-base-millis:1000}") long mcpRetryBaseMillis) {
        this(RestClient.builder()
                        .requestFactory(new SimpleClientHttpRequestFactory())
                        .build(),
                Executors.newVirtualThreadPerTaskExecutor(),
                new HttpClientMcpClientFactory(),
                mcpRetryBaseMillis,
                defaultTimeout);
    }

    // package-private: existing http-path tests build the dispatcher with a RestClient + executor.
    ToolDispatcher(RestClient http, ExecutorService executor) {
        this(http, executor, new HttpClientMcpClientFactory(), 1000L, 30);
    }

    // package-private: mcp tests inject a stub factory + a zero retry base so retries don't sleep.
    ToolDispatcher(McpClientFactory mcpClientFactory, ExecutorService executor,
                   long mcpRetryBaseMillis, int defaultTimeoutSeconds) {
        this(RestClient.builder().requestFactory(new SimpleClientHttpRequestFactory()).build(),
                executor, mcpClientFactory, mcpRetryBaseMillis, defaultTimeoutSeconds);
    }

    private ToolDispatcher(RestClient http, ExecutorService executor, McpClientFactory mcpClientFactory,
                           long mcpRetryBaseMillis, int defaultTimeoutSeconds) {
        this.http = http;
        this.executor = executor;
        this.mcpClientFactory = mcpClientFactory;
        this.mcpRetryBaseMillis = mcpRetryBaseMillis;
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
    }

    // ==== HTTP path ====================================================================

    public CompletableFuture<ToolResult> dispatchHttp(
            ToolDef tool, ToolUseParser.Block block, String runId, String webhookToken) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                return callOnce(tool, block, runId, webhookToken);
            } catch (TransientToolError e) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                try {
                    return callOnce(tool, block, runId, webhookToken);
                } catch (Exception second) {
                    return errorResult(block.id(), "tool_error: " + second.getMessage());
                }
            } catch (Exception e) {
                return errorResult(block.id(), "tool_error: " + e.getMessage());
            }
        }, executor);
    }

    private ToolResult callOnce(ToolDef tool, ToolUseParser.Block block, String runId, String webhookToken) {
        var body = Map.<String, Object>of(
                "run_id", runId,
                "tool_name", block.name(),
                "input", block.input()
        );
        var resp = http.post()
                .uri(tool.webhook_url())
                .header("Authorization", "Bearer " + webhookToken)
                .header("X-Vistierie-Run-Id", runId)
                .header("X-Vistierie-Tool", block.name())
                .header("content-type", "application/json")
                .body(body)
                .retrieve()
                .onStatus(s -> s.is5xxServerError(), (req, res) -> {
                    throw new TransientToolError(res.getStatusCode().value() + ": " + new String(res.getBody().readAllBytes()));
                })
                .onStatus(s -> s.is4xxClientError(), (req, res) -> {
                    throw new RuntimeException("4xx: " + res.getStatusCode().value() + " " + new String(res.getBody().readAllBytes()));
                })
                .body(JsonNode.class);
        JsonNode content = resp.has("output") ? resp.path("output") : resp;
        return new ToolResult(block.id(), false, content);
    }

    // ==== MCP path ====================================================================

    /**
     * Dispatch a call to a remote MCP tool. Blank credential short-circuits to an error result
     * (no network). Otherwise up to {@value #MCP_MAX_RETRIES} retries with exponential backoff;
     * each failed attempt evicts and rebuilds the cached client (see {@link #callMcpOnce}). A
     * tool-level {@code isError=true} envelope is treated as a retryable failure per the spec.
     */
    public CompletableFuture<ToolResult> dispatchMcp(
            ToolDef tool, ToolUseParser.Block block, String runId, String mcpToken) {

        return CompletableFuture.supplyAsync(() -> {
            if (mcpToken == null || mcpToken.isBlank()) {
                return errorResult(block.id(), "tool_error: no mcp credential for " + tool.mcp_server_url());
            }
            RuntimeException last = null;
            for (int attempt = 0; attempt <= MCP_MAX_RETRIES; attempt++) {
                if (attempt > 0) {
                    long backoff = mcpRetryBaseMillis << (attempt - 1); // 1x, 2x, 4x of base
                    if (backoff > 0) {
                        try {
                            Thread.sleep(backoff);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                try {
                    return callMcpOnce(tool, block, mcpToken);
                } catch (RuntimeException e) {
                    last = e; // callMcpOnce already evicted + closed the holder
                }
            }
            return errorResult(block.id(), "tool_error: " + (last != null ? last.getMessage() : "unknown"));
        }, executor);
    }

    private ToolResult callMcpOnce(ToolDef tool, ToolUseParser.Block block, String mcpToken) {
        // The token MUST be part of the cache key: same server + different token => different client,
        // otherwise tenant A's bearer could be reused for tenant B on a shared upstream.
        String key = tool.mcp_server_url() + " " + mcpToken;
        Duration timeout = Duration.ofSeconds(
                tool.mcp_timeout_seconds() != null ? tool.mcp_timeout_seconds() : defaultTimeoutSeconds);

        // Build the client OUTSIDE any map lock: create() does a blocking initialize() handshake,
        // and ConcurrentHashMap holds a bin lock for the whole computeIfAbsent mapping function —
        // which would serialize construction of DIFFERENT keys that hash into the same bin, breaking
        // the "different (server,token) pairs run in parallel" guarantee. get-then-putIfAbsent keeps
        // construction lock-free; a racing loser closes its redundant client.
        McpClientHolder holder = mcpClients.get(key);
        if (holder == null) {
            McpClientHandle handle = mcpClientFactory.create(tool.mcp_server_url(), mcpToken, timeout);
            McpClientHolder fresh = new McpClientHolder(handle);
            McpClientHolder existing = mcpClients.putIfAbsent(key, fresh);
            if (existing != null) { // lost the race — another thread already installed one
                try { handle.close(); } catch (RuntimeException ignored) { /* best effort */ }
                holder = existing;
            } else {
                holder = fresh;
            }
        }
        try {
            // McpSyncClient is not thread-safe; serialize all calls on one holder.
            synchronized (holder) {
                JsonNode content = holder.handle.callTool(tool.resolvedMcpToolName(), block.input());
                return new ToolResult(block.id(), false, content);
            }
        } catch (RuntimeException e) {
            // Drop the (possibly stale) client so the retry loop rebuilds a fresh one.
            mcpClients.remove(key, holder);
            try { holder.handle.close(); } catch (RuntimeException ignored) { /* best effort */ }
            throw e;
        }
    }

    private ToolResult errorResult(String id, String msg) {
        return new ToolResult(id, true, JsonNodeFactory.instance.objectNode().put("error", msg));
    }

    private static class TransientToolError extends RuntimeException {
        TransientToolError(String m) { super(m); }
    }

    // ==== MCP client seam =============================================================

    /** A live connection to one remote MCP server; one holder per (server, token) cache entry. */
    interface McpClientHandle extends AutoCloseable {
        /**
         * Call the remote tool and return its OUTPUT parsed as a {@link JsonNode}. Throws a
         * {@link RuntimeException} on {@code res.isError()==true}, empty/missing content, or any
         * transport failure.
         */
        JsonNode callTool(String toolName, JsonNode input);

        @Override
        void close();
    }

    /** Builds a {@link McpClientHandle} for a given (server, token, timeout). */
    interface McpClientFactory {
        McpClientHandle create(String serverUrl, String token, Duration timeout);
    }

    /** Pairs an {@link McpClientHandle} with a monitor object for per-holder serialization. */
    static final class McpClientHolder {
        final McpClientHandle handle;
        McpClientHolder(McpClientHandle handle) { this.handle = handle; }
    }

    /**
     * Production factory: builds a real {@link McpSyncClient} over Streamable HTTP, injecting the
     * bearer token via {@code httpRequestCustomizer}. SDK types stay confined to this class and its
     * handle — {@link ToolDispatcher}'s own logic never touches {@code McpSchema}/{@code McpSyncClient}.
     */
    static final class HttpClientMcpClientFactory implements McpClientFactory {
        @Override
        public McpClientHandle create(String serverUrl, String token, Duration timeout) {
            var transport = HttpClientStreamableHttpTransport.builder(serverUrl)
                    .endpoint("/mcp")
                    .connectTimeout(timeout)
                    .httpRequestCustomizer((b, method, uri, body, ctx) ->
                            b.setHeader("Authorization", "Bearer " + token))
                    .build();
            McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(timeout)
                    .build();
            client.initialize();
            return new SdkMcpClientHandle(client);
        }
    }

    /** Wraps an {@link McpSyncClient}, mapping MCP results/errors to {@link JsonNode}/exceptions. */
    static final class SdkMcpClientHandle implements McpClientHandle {
        private static final ObjectMapper MAPPER = new ObjectMapper();
        private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

        private final McpSyncClient client;

        SdkMcpClientHandle(McpSyncClient client) { this.client = client; }

        @Override
        public JsonNode callTool(String toolName, JsonNode input) {
            Map<String, Object> argsMap = (input == null || input.isNull() || input.isMissingNode())
                    ? Map.of()
                    : MAPPER.convertValue(input, MAP_TYPE);
            McpSchema.CallToolResult res = client.callTool(new McpSchema.CallToolRequest(toolName, argsMap));
            if (Boolean.TRUE.equals(res.isError()) || res.content() == null || res.content().isEmpty()) {
                String detail = (res.content() != null && !res.content().isEmpty())
                        ? ((McpSchema.TextContent) res.content().getFirst()).text()
                        : "empty response";
                throw new RuntimeException("mcp tool '" + toolName + "' error: " + detail);
            }
            String text = ((McpSchema.TextContent) res.content().getFirst()).text();
            return MAPPER.readTree(text);
        }

        @Override
        public void close() {
            try { client.closeGracefully(); } catch (RuntimeException ignored) { /* best effort */ }
        }
    }
}
