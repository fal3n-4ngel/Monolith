package com.dashboard.api.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Async and outbound-HTTP wiring for the audit pipeline.
 *
 * <p>Both beans here exist to keep slow downstreams off the request thread — the single
 * biggest availability risk in a Cloud Run service billed by CPU-second.
 */
@Configuration
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(AuditProperties.class)
public class AuditInfrastructureConfig {

    /**
     * Outbound webhook client with hard timeouts. The default request factory has
     * <i>no</i> connect or read timeout, so an unresponsive endpoint blocks forever.
     */
    @Bean
    RestClient webhookRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(factory).build();
    }

    /**
     * Small bounded pool for security alerts.
     *
     * <p>The queue is deliberately shallow and overflow is discarded rather than run on the
     * caller: alerting is best-effort, the audit record is persisted either way, and
     * per-origin deduplication already caps the sustained rate.
     */
    @Bean("alertExecutor")
    TaskExecutor alertExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("audit-alert-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
