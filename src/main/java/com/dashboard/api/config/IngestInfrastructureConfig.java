package com.dashboard.api.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Async wiring for the ingest pipeline — keeps BigQuery writes off the request thread, the
 * single biggest availability risk in a Cloud Run service billed by CPU-second.
 */
@Configuration
@EnableAsync
@EnableConfigurationProperties(AuditProperties.class)
public class IngestInfrastructureConfig {

    @Bean("bigqueryExecutor")
    TaskExecutor bigqueryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("ingest-bq-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    /**
     * Separate from {@link #bigqueryExecutor()} on purpose — observed in production: the
     * BigQuery client's own internal retry/backoff can run well past a minute on a bad
     * connection, and with corePoolSize(1) on a bounded queue that holds the single worker
     * thread the whole time. Sharing a pool meant a slow BigQuery insert could silently delay
     * a Discord notification by minutes. The notifier needs to fail fast and independently.
     */
    @Bean("discordExecutor")
    TaskExecutor discordExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ingest-discord-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
