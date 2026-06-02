package de.vesterion.vistierie.streaming;

import de.vesterion.vistierie.agent.runner.AgentDispatcher;
import de.vesterion.vistierie.agents.Agent;
import de.vesterion.vistierie.budget.BudgetEnforcer;
import de.vesterion.vistierie.budget.BudgetException;
import de.vesterion.vistierie.kill.KillSwitchService;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Component
public class StreamingSessionCoordinator {

    private static final Logger log = LoggerFactory.getLogger(StreamingSessionCoordinator.class);

    private final StreamingSessionRepository sessions;
    private final EventSourcePoller poller;
    private final AgentDispatcher dispatcher;
    private final KillSwitchService kill;
    private final BudgetEnforcer budgets;
    private final TenantRepository tenants;
    private final Clock clock;

    public StreamingSessionCoordinator(StreamingSessionRepository sessions,
                                        EventSourcePoller poller,
                                        AgentDispatcher dispatcher,
                                        KillSwitchService kill,
                                        BudgetEnforcer budgets,
                                        TenantRepository tenants,
                                        Clock clock) {
        this.sessions = sessions;
        this.poller = poller;
        this.dispatcher = dispatcher;
        this.kill = kill;
        this.budgets = budgets;
        this.tenants = tenants;
        this.clock = clock;
    }

    /**
     * Called from AgentScheduler.tick() for each streaming agent after the
     * cron boundary is reached (i.e. the cron has fired for this tick).
     *
     * OPEN: if no open session exists, insert one.
     * POLL: if an open session exists and poll is due, poll and spawn runs.
     * CLOSE: if the window has expired, mark closed.
     *
     * @param agent    the streaming agent (sessionDurationSeconds != null)
     * @param cronFired true when the cron boundary was reached this tick
     */
    public void handleTick(Agent agent, boolean cronFired) {
        var now = clock.instant();
        var existing = sessions.findOpenByAgent(agent.id());

        // CLOSE expired sessions first
        if (existing.isPresent() && !now.isBefore(existing.get().closesAt())) {
            log.info("streaming-bee: closing expired session {} for agent {}",
                    existing.get().id(), agent.name());
            sessions.markClosed(existing.get().id());
            existing = java.util.Optional.empty();
        }

        // OPEN: cron boundary reached and no open session
        if (existing.isEmpty() && cronFired) {
            var sessionId = UUID.randomUUID();
            int durationSecs = agent.sessionDurationSeconds(); // non-null guaranteed by caller
            var closesAt = now.plusSeconds(durationSecs);
            log.info("streaming-bee: opening session {} for agent {} closes_at={}",
                    sessionId, agent.name(), closesAt);
            sessions.insertOpen(sessionId, agent.tenantId(), agent.id(), now, closesAt);
            existing = sessions.findOpenByAgent(agent.id());
        }

        if (existing.isEmpty()) return;
        var session = existing.get();

        // CLOSE check again (edge case: just-opened session with 0 duration — not valid per spec)
        if (!now.isBefore(session.closesAt())) {
            sessions.markClosed(session.id());
            return;
        }

        // POLL: check if poll is due
        int pollInterval = agent.pollIntervalSeconds() != null ? agent.pollIntervalSeconds() : 60;
        var lastPoll = session.lastPollAt();
        boolean pollDue = lastPoll == null ||
                now.getEpochSecond() - lastPoll.getEpochSecond() >= pollInterval;
        if (!pollDue) return;

        // Kill check
        try { kill.check(agent.tenantId()); }
        catch (KillSwitchService.KilledException e) {
            log.warn("streaming-bee: poll skipped for agent {} session {}: tenant killed ({})",
                    agent.name(), session.id(), e.reason());
            sessions.updateLastPoll(session.id(), now);
            return;
        }

        // Poll the event source
        var events = poller.poll(
                agent.eventSourceUrl(),
                agent.webhookToken(),
                session.id(),
                agent.name(),
                lastPoll,
                now);

        // Spawn a child run per event
        for (JsonNode event : events) {
            try {
                var tenantName = tenants.findById(agent.tenantId()).orElseThrow().name();
                budgets.checkOrThrow(agent.tenantId(), tenantName, agent.id(), agent.name());
            } catch (BudgetException e) {
                log.warn("streaming-bee: run spawn skipped for agent {} session {}: budget gate ({})",
                        agent.name(), session.id(), e.code());
                continue;
            }
            try {
                var runId = dispatcher.trigger(
                        agent.tenantId(), agent, "session_event", event,
                        agent.completionWebhook(), agent.completionWebhookToken(),
                        session.id());
                log.debug("streaming-bee: spawned run {} for agent {} session {} event={}",
                        runId, agent.name(), session.id(), event);
            } catch (Exception e) {
                log.warn("streaming-bee: failed to spawn run for agent {} session {}: {}",
                        agent.name(), session.id(), e.getMessage());
            }
        }

        sessions.updateLastPoll(session.id(), now);
    }
}
