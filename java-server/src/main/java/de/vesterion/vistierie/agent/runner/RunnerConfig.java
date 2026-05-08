package de.vesterion.vistierie.agent.runner;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableAsync
public class RunnerConfig {

    @Bean(name = "agentExecutor")
    public ExecutorService agentExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public ExecutorService subagentExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public RecursionGuard recursionGuard(@Value("${vistierie.agents.subagent.max-depth:5}") int max) {
        return new RecursionGuard(max);
    }
}
