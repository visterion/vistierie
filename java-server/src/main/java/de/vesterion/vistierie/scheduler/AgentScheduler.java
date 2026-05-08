package de.vesterion.vistierie.scheduler;

import de.vesterion.vistierie.agent.runner.AgentDispatcher;
import de.vesterion.vistierie.agents.Agent;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.kill.KillSwitchService;
import de.vesterion.vistierie.runs.RunEventRecorder;
import de.vesterion.vistierie.runs.RunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;

@Component
public class AgentScheduler {

    private static final Logger log = LoggerFactory.getLogger(AgentScheduler.class);

    private final AgentRepository agents;
    private final RunRepository runs;
    private final AgentDispatcher dispatcher;
    private final KillSwitchService kill;
    private final RunEventRecorder events;
    private final Clock clock;
    private final ObjectMapper mapper;

    public AgentScheduler(AgentRepository agents, RunRepository runs,
                          AgentDispatcher dispatcher, KillSwitchService kill,
                          RunEventRecorder events, Clock clock, ObjectMapper mapper) {
        this.agents = agents;
        this.runs = runs;
        this.dispatcher = dispatcher;
        this.kill = kill;
        this.events = events;
        this.clock = clock;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelayString = "${vistierie.agents.scheduler.tick-millis:30000}")
    public void scheduledTick() { tick(); }

    /** Visible for tests. */
    public void tick() {
        var now = clock.instant();
        for (Agent a : agents.findScheduled()) {
            CronExpression expr;
            try {
                expr = CronExpression.parse(a.schedule());
            } catch (IllegalArgumentException e) {
                log.warn("agent {} has invalid schedule '{}': {}", a.id(), a.schedule(), e.getMessage());
                continue;
            }
            var prev = a.lastTickAt() != null ? a.lastTickAt() : a.createdAt();
            var next = expr.next(ZonedDateTime.ofInstant(prev, ZoneOffset.UTC));
            if (next == null || next.toInstant().isAfter(now)) continue;

            // Tenant kill check
            try { kill.check(a.tenantId()); }
            catch (KillSwitchService.KilledException e) {
                log.warn("agent {} cron skipped: tenant {} killed ({})", a.id(), a.tenantId(), e.reason());
                agents.updateLastTick(a.id(), now);
                continue;
            }

            // Skip-if-running
            if (runs.hasOpenRun(a.id())) {
                runs.latestOpenRunId(a.id()).ifPresent(rid ->
                        events.record(rid, "warn", "cron_skipped",
                                mapper.valueToTree(Map.of("reason", "overlap"))));
                agents.updateLastTick(a.id(), now);
                continue;
            }

            // Fire
            try {
                dispatcher.trigger(a.tenantId(), a, "cron",
                        mapper.createObjectNode(), null, null);
                agents.updateLastTick(a.id(), now);
            } catch (Exception e) {
                log.warn("agent {} cron dispatch failed: {}", a.id(), e.getMessage());
            }
        }
    }
}
