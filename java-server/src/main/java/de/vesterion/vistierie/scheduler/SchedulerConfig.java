package de.vesterion.vistierie.scheduler;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration
@EnableScheduling
public class SchedulerConfig {

    /**
     * Production system clock. Intentionally NOT marked {@code @Primary}: tests
     * register their own {@code @Primary Clock} bean via {@code @TestConfiguration},
     * and Spring rejects two beans both carrying {@code @Primary} of the same type.
     * If a future task needs to inject a non-primary {@code Clock} alongside this one,
     * use {@code @Bean("systemClock")} + {@code @Qualifier} at the consumer site.
     */
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
