package com.dashboard.api.config;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Mirrors {@link FirestoreConfig}: lazy on purpose, so building the client (which resolves
 * Application Default Credentials) doesn't happen on every cold start, including the ones
 * that only serve a health probe, and the service still boots without GCP credentials.
 */
@Configuration
public class BigQueryConfig {

    private static final Logger log = LoggerFactory.getLogger(BigQueryConfig.class);

    @Value("${dashboard.gcp.project-id:}")
    private String projectId;

    @Bean
    @Lazy
    public BigQuery bigQuery() {
        BigQueryOptions.Builder options = BigQueryOptions.newBuilder();
        if (projectId != null && !projectId.isBlank()) {
            options.setProjectId(projectId.trim());
        }
        BigQuery client = options.build().getService();
        log.info("[BigQuery] Client initialized for project [{}]", projectId);
        return client;
    }
}
