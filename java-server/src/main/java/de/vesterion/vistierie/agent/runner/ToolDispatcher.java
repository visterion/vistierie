package de.vesterion.vistierie.agent.runner;

import de.vesterion.vistierie.agents.dto.ToolDef;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class ToolDispatcher {

    private final RestClient http;
    private final ExecutorService executor;

    public ToolDispatcher(@Value("${vistierie.agents.tool-default-timeout-seconds:30}") int defaultTimeout) {
        this(RestClient.builder()
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build(),
             Executors.newVirtualThreadPerTaskExecutor());
    }

    // package-private for tests
    ToolDispatcher(RestClient http, ExecutorService executor) {
        this.http = http;
        this.executor = executor;
    }

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

    private ToolResult errorResult(String id, String msg) {
        return new ToolResult(id, true, JsonNodeFactory.instance.objectNode().put("error", msg));
    }

    private static class TransientToolError extends RuntimeException {
        TransientToolError(String m) { super(m); }
    }
}
