package de.vesterion.vistierie.agent.runner;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class RunnerConfig {

    @Bean
    ExecutorService subagentExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    RecursionGuard recursionGuard(@Value("${vistierie.agents.subagent.max-depth:5}") int max) {
        return new RecursionGuard(max);
    }
}
