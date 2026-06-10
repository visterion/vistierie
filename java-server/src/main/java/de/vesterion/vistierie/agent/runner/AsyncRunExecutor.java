package de.vesterion.vistierie.agent.runner;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Runs an agent run asynchronously on the {@code agentExecutor} pool.
 * Kept in its own bean so Spring's @Async proxy applies — calling an @Async method
 * from within the same bean (as AgentDispatcher used to) bypasses the proxy and runs
 * synchronously on the caller's thread.
 */
@Component
public class AsyncRunExecutor {

    private final AgentRunner runner;

    public AsyncRunExecutor(AgentRunner runner) {
        this.runner = runner;
    }

    @Async("agentExecutor")
    public void execute(String runId) {
        runner.execute(runId);
    }
}
