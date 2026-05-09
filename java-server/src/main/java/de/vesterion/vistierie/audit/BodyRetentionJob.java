package de.vesterion.vistierie.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

@Component
public class BodyRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(BodyRetentionJob.class);

    private final LlmCallBodyRepository bodies;
    private final AuditProperties props;

    public BodyRetentionJob(LlmCallBodyRepository bodies, AuditProperties props) {
        this.bodies = bodies;
        this.props = props;
    }

    @Scheduled(fixedDelay = 24, timeUnit = TimeUnit.HOURS, initialDelay = 1)
    public void cleanup() {
        int days = props.getBodyRetentionDays();
        if (days <= 0) return;
        var cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        int deleted = bodies.deleteOlderThan(cutoff);
        if (deleted > 0) {
            log.info("BodyRetentionJob: deleted {} bodies older than {}", deleted, cutoff);
        }
    }
}
