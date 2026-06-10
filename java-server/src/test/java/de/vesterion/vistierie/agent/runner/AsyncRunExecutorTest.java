package de.vesterion.vistierie.agent.runner;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AsyncRunExecutorTest {

    @Test void executeDelegatesToRunner() {
        AgentRunner runner = mock(AgentRunner.class);
        var executor = new AsyncRunExecutor(runner);

        executor.execute("RUN42");

        verify(runner).execute("RUN42");
    }
}
