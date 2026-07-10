package de.vesterion.vistierie.streaming;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class EventSourcePoller {

    private static final Logger log = LoggerFactory.getLogger(EventSourcePoller.class);

    private final RestClient http;
    private final ObjectMapper mapper;

    @Autowired
    public EventSourcePoller(ObjectMapper mapper) {
        this(RestClient.builder()
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build(), mapper);
    }

    // package-private for tests
    EventSourcePoller(RestClient http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    /**
     * A poll's outcome. {@code ok=false} signals a hard failure (persistent
     * 5xx after retry, connection error, or any other unexpected exception);
     * callers must not treat this the same as a successful poll that simply
     * found zero events, since doing so would let the caller advance its
     * poll cursor and permanently skip the events that occurred during the
     * failed window.
     */
    public record PollResult(boolean ok, List<JsonNode> events) {
        static PollResult ok(List<JsonNode> events) { return new PollResult(true, events); }
        static PollResult failed() { return new PollResult(false, List.of()); }
    }

    /**
     * POST the event-source webhook and return the poll outcome.
     * On hard failure returns {@link PollResult#failed()} and logs — never throws.
     */
    public PollResult poll(String url, String token, UUID sessionId,
                            String agentName, Instant since, Instant now) {
        try {
            return PollResult.ok(callOnce(url, token, sessionId, agentName, since, now));
        } catch (TransientPollError e) {
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            try {
                return PollResult.ok(callOnce(url, token, sessionId, agentName, since, now));
            } catch (Exception second) {
                log.warn("EventSourcePoller: retry failed for agent {} session {}: {}",
                        agentName, sessionId, second.getMessage());
                return PollResult.failed();
            }
        } catch (Exception e) {
            log.warn("EventSourcePoller: poll failed for agent {} session {}: {}",
                    agentName, sessionId, e.getMessage());
            return PollResult.failed();
        }
    }

    private List<JsonNode> callOnce(String url, String token, UUID sessionId,
                                     String agentName, Instant since, Instant now) {
        var bodyNode = JsonNodeFactory.instance.objectNode();
        bodyNode.put("session_id", sessionId.toString());
        bodyNode.put("agent", agentName);
        if (since != null) {
            bodyNode.put("since", since.toString());
        } else {
            bodyNode.putNull("since");
        }
        bodyNode.put("now", now.toString());

        JsonNode resp = http.post()
                .uri(url)
                .header("Authorization", "Bearer " + token)
                .header("content-type", "application/json")
                .body(bodyNode)
                .retrieve()
                .onStatus(s -> s.is5xxServerError(), (req, res) -> {
                    throw new TransientPollError(res.getStatusCode().value()
                            + ": " + readBody(res));
                })
                .onStatus(s -> s.is4xxClientError(), (req, res) -> {
                    throw new RuntimeException("4xx: " + res.getStatusCode().value()
                            + " " + readBody(res));
                })
                .body(JsonNode.class);

        if (resp == null || !resp.has("events")) {
            return List.of();
        }
        var events = resp.get("events");
        if (!events.isArray()) return List.of();
        var result = new ArrayList<JsonNode>(events.size());
        for (var e : events) result.add(e);
        return result;
    }

    private String readBody(org.springframework.http.client.ClientHttpResponse res) {
        try { return new String(res.getBody().readAllBytes()); }
        catch (Exception e) { return "(unreadable)"; }
    }

    private static class TransientPollError extends RuntimeException {
        TransientPollError(String m) { super(m); }
    }
}
