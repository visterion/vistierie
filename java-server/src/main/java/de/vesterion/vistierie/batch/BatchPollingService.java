package de.vesterion.vistierie.batch;

import de.vesterion.vistierie.kill.KillSwitchService;
import de.vesterion.vistierie.provider.ProviderRegistry;
import de.vesterion.vistierie.runs.Run;
import de.vesterion.vistierie.runs.RunRepository;
import de.vesterion.vistierie.runs.RunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class BatchPollingService {

    private static final Logger log = LoggerFactory.getLogger(BatchPollingService.class);

    private final RunRepository runRepo;
    private final ProviderRegistry providers;
    private final BatchService batchService;
    private final RunStore runStore;
    private final KillSwitchService kill;

    public BatchPollingService(RunRepository runRepo, ProviderRegistry providers,
                               BatchService batchService, RunStore runStore,
                               KillSwitchService kill) {
        this.runRepo = runRepo;
        this.providers = providers;
        this.batchService = batchService;
        this.runStore = runStore;
        this.kill = kill;
    }

    @Scheduled(fixedDelayString = "${vistierie.agents.batch.poll-millis:60000}")
    public void scheduledTick() { tick(); }

    /** Visible for tests. */
    public void tick() {
        for (Run parent : runRepo.findOpenBatchParents()) {
            try {
                kill.check(parent.tenantId());
            } catch (KillSwitchService.KilledException e) {
                killBatch(parent, e.reason());
                continue;
            }
            try {
                var status = providers.get("anthropic").getBatch(parent.anthropicBatchId());
                if ("ended".equals(status.processingStatus()) && status.resultsUrl() != null) {
                    var stream = providers.get("anthropic").streamResults(status.resultsUrl());
                    batchService.finalize(parent.id(), parent.anthropicBatchId(), stream);
                }
                // else still in_progress / canceling — leave for next tick
            } catch (Exception e) {
                log.warn("batch poll failed for run {} batch {}: {}",
                        parent.id(), parent.anthropicBatchId(), e.getMessage());
            }
        }
    }

    private void killBatch(Run parent, String reason) {
        for (Run child : runRepo.findByParent(parent.id())) {
            if ("queued".equals(child.status()) || "running".equals(child.status())) {
                runStore.markTerminal(child.id(), "failed", null, "killed: " + reason, null);
            }
        }
        runStore.markTerminal(parent.id(), "failed", null, "killed: " + reason, null);
    }
}
