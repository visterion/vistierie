package de.vesterion.vistierie.streaming;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class EventSourcePollerTest {

    WireMockServer wm;
    EventSourcePoller poller;
    static final ObjectMapper M = new ObjectMapper();

    @BeforeEach void up() {
        wm = new WireMockServer(0);
        wm.start();
        configureFor("localhost", wm.port());
        var http = RestClient.builder()
                .requestFactory(new SimpleClientHttpRequestFactory()).build();
        poller = new EventSourcePoller(http, M);
    }

    @AfterEach void down() { wm.stop(); }

    @Test void happyPath_returnsEvents() throws Exception {
        stubFor(post(urlEqualTo("/events")).willReturn(okJson("""
                {"events":[{"symbol":"AAPL","type":"spike"},{"symbol":"MSFT","type":"spike"}]}
                """)));
        var sessionId = UUID.randomUUID();
        var now = Instant.parse("2026-06-02T14:00:00Z");
        var since = Instant.parse("2026-06-02T13:55:00Z");

        var events = poller.poll(
                "http://localhost:" + wm.port() + "/events",
                "bearer-token", sessionId, "daywalker", since, now);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).get("symbol").asText()).isEqualTo("AAPL");
        assertThat(events.get(1).get("symbol").asText()).isEqualTo("MSFT");
        verify(postRequestedFor(urlEqualTo("/events"))
                .withHeader("Authorization", equalTo("Bearer bearer-token"))
                .withHeader("content-type", equalTo("application/json")));
    }

    @Test void emptyEvents_returnsEmptyList() {
        stubFor(post(urlEqualTo("/events")).willReturn(okJson("{\"events\":[]}")));
        var events = poller.poll(
                "http://localhost:" + wm.port() + "/events",
                "tok", UUID.randomUUID(), "daywalker",
                null, Instant.now());
        assertThat(events).isEmpty();
    }

    @Test void missingEventsKey_returnsEmptyList() {
        stubFor(post(urlEqualTo("/events")).willReturn(okJson("{\"ok\":true}")));
        var events = poller.poll(
                "http://localhost:" + wm.port() + "/events",
                "tok", UUID.randomUUID(), "daywalker",
                null, Instant.now());
        assertThat(events).isEmpty();
    }

    @Test void sinceIsNullified_whenNotProvided() throws Exception {
        stubFor(post(urlEqualTo("/events")).willReturn(okJson("{\"events\":[]}")));
        poller.poll("http://localhost:" + wm.port() + "/events",
                "tok", UUID.randomUUID(), "daywalker",
                null, Instant.now());
        verify(postRequestedFor(urlEqualTo("/events"))
                .withRequestBody(matchingJsonPath("$[?(@.since == null)]")));
    }

    @Test void retryOn5xx_secondSucceeds() {
        stubFor(post(urlEqualTo("/events"))
                .inScenario("retry").whenScenarioStateIs("Started")
                .willReturn(serverError())
                .willSetStateTo("ok"));
        stubFor(post(urlEqualTo("/events"))
                .inScenario("retry").whenScenarioStateIs("ok")
                .willReturn(okJson("{\"events\":[{\"x\":1}]}")));

        var events = poller.poll(
                "http://localhost:" + wm.port() + "/events",
                "tok", UUID.randomUUID(), "daywalker",
                null, Instant.now());
        assertThat(events).hasSize(1);
        verify(2, postRequestedFor(urlEqualTo("/events")));
    }

    @Test void persistent5xx_returnsEmptyList_doesNotThrow() {
        stubFor(post(anyUrl()).willReturn(serverError()));
        var events = poller.poll(
                "http://localhost:" + wm.port() + "/events",
                "tok", UUID.randomUUID(), "daywalker",
                null, Instant.now());
        assertThat(events).isEmpty();
    }

    @Test void connectionError_returnsEmptyList_doesNotThrow() {
        // port 1 is almost certainly closed
        var events = poller.poll(
                "http://localhost:1/events",
                "tok", UUID.randomUUID(), "daywalker",
                null, Instant.now());
        assertThat(events).isEmpty();
    }

    @Test void requestBodyContainsExpectedFields() throws Exception {
        stubFor(post(urlEqualTo("/events")).willReturn(okJson("{\"events\":[]}")));
        var sessionId = UUID.randomUUID();
        var now = Instant.parse("2026-06-02T14:00:00Z");
        var since = Instant.parse("2026-06-02T13:55:00Z");
        poller.poll("http://localhost:" + wm.port() + "/events",
                "tok", sessionId, "daywalker", since, now);
        verify(postRequestedFor(urlEqualTo("/events"))
                .withRequestBody(matchingJsonPath("$.session_id",
                        equalTo(sessionId.toString())))
                .withRequestBody(matchingJsonPath("$.agent", equalTo("daywalker")))
                .withRequestBody(matchingJsonPath("$.since",
                        equalTo(since.toString())))
                .withRequestBody(matchingJsonPath("$.now",
                        equalTo(now.toString()))));
    }
}
