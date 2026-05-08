package de.vesterion.vistierie.agent.runner;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.vesterion.vistierie.agents.dto.ToolDef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class ToolDispatcherTest {

    WireMockServer wm;
    ToolDispatcher dispatcher;
    static final ObjectMapper M = new ObjectMapper();

    @BeforeEach void up() {
        wm = new WireMockServer(0);
        wm.start();
        configureFor("localhost", wm.port());
        var http = RestClient.builder().requestFactory(new SimpleClientHttpRequestFactory()).build();
        dispatcher = new ToolDispatcher(http, Executors.newVirtualThreadPerTaskExecutor());
    }
    @AfterEach void down() { wm.stop(); }

    @Test void httpToolHappyPath() throws Exception {
        stubFor(post(urlEqualTo("/tools/cell.read")).willReturn(okJson("""
                {"output":{"text":"hello"}}
                """)));
        var tool = new ToolDef("cell.read", "d", M.readTree("{\"type\":\"object\"}"),
                null, null, "http://localhost:" + wm.port() + "/tools/cell.read", 5);
        var block = new ToolUseParser.Block("toolu_1", "cell.read", M.readTree("{\"id\":\"c1\"}"));

        var result = dispatcher.dispatchHttp(tool, block, "run-x", "wt-token").get();

        assertThat(result.toolUseId()).isEqualTo("toolu_1");
        assertThat(result.isError()).isFalse();
        assertThat(result.content().path("text").asText()).isEqualTo("hello");
        verify(postRequestedFor(urlEqualTo("/tools/cell.read"))
                .withHeader("Authorization", equalTo("Bearer wt-token"))
                .withHeader("X-Vistierie-Run-Id", equalTo("run-x")));
    }

    @Test void retriesOn5xxOnce() throws Exception {
        stubFor(post(urlEqualTo("/t"))
                .inScenario("retry").whenScenarioStateIs("Started")
                .willReturn(serverError())
                .willSetStateTo("ok"));
        stubFor(post(urlEqualTo("/t"))
                .inScenario("retry").whenScenarioStateIs("ok")
                .willReturn(okJson("{\"output\":\"good\"}")));
        var tool = new ToolDef("t", "d", M.readTree("{\"type\":\"object\"}"),
                null, null, "http://localhost:" + wm.port() + "/t", 5);
        var block = new ToolUseParser.Block("u1", "t", M.readTree("{}"));
        var r = dispatcher.dispatchHttp(tool, block, "r1", "wt").get();
        assertThat(r.isError()).isFalse();
        assertThat(r.content().asText()).isEqualTo("good");
    }

    @Test void parallelDispatch() throws Exception {
        stubFor(post(anyUrl()).willReturn(okJson("{\"output\":\"x\"}")));
        var tool = new ToolDef("t", "d", M.readTree("{\"type\":\"object\"}"),
                null, null, "http://localhost:" + wm.port() + "/t", 5);
        var blocks = List.of(
                new ToolUseParser.Block("u1", "t", M.readTree("{}")),
                new ToolUseParser.Block("u2", "t", M.readTree("{}")),
                new ToolUseParser.Block("u3", "t", M.readTree("{}")));
        var futures = new ConcurrentLinkedQueue<java.util.concurrent.CompletableFuture<ToolResult>>();
        for (var b : blocks) futures.add(dispatcher.dispatchHttp(tool, b, "r", "wt"));
        for (var f : futures) f.get();
        verify(3, postRequestedFor(urlEqualTo("/t")));
    }
}
