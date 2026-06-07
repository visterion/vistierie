package de.vesterion.vistierie.scheduler;

import org.springframework.scheduling.support.CronExpression;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Single source of truth for "when does this 6-field Spring cron next fire".
 * Evaluated in UTC — the timezone the {@link AgentScheduler} fires in.
 */
public final class CronSchedules {
    private CronSchedules() {}

    /** Next fire instant strictly after {@code from}, or null if the schedule is blank/invalid. */
    public static Instant nextRunAt(String schedule, Instant from) {
        if (schedule == null || schedule.isBlank()) return null;
        try {
            ZonedDateTime next = CronExpression.parse(schedule.trim())
                    .next(ZonedDateTime.ofInstant(from, ZoneOffset.UTC));
            return next == null ? null : next.toInstant();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
