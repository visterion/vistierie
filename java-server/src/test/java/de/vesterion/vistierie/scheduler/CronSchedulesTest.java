package de.vesterion.vistierie.scheduler;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class CronSchedulesTest {
    @Test
    void computesNextWeekdayFireInUtc() {
        // Wed 2026-06-10T12:00:00Z → next "0 0 4 * * 1-5" is Thu 2026-06-11T04:00:00Z
        Instant next = CronSchedules.nextRunAt("0 0 4 * * 1-5", Instant.parse("2026-06-10T12:00:00Z"));
        assertThat(next).isEqualTo(Instant.parse("2026-06-11T04:00:00Z"));
    }
    @Test
    void blankScheduleIsNull() {
        assertThat(CronSchedules.nextRunAt("", Instant.parse("2026-06-10T12:00:00Z"))).isNull();
        assertThat(CronSchedules.nextRunAt(null, Instant.parse("2026-06-10T12:00:00Z"))).isNull();
    }
    @Test
    void invalidScheduleIsNull() {
        assertThat(CronSchedules.nextRunAt("garbage", Instant.parse("2026-06-10T12:00:00Z"))).isNull();
    }
}
