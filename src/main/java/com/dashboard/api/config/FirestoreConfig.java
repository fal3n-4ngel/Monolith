package com.dashboard.api.config;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class FirestoreConfig {

    private static final Logger log = LoggerFactory.getLogger(FirestoreConfig.class);

    @Value("${dashboard.gcp.project-id:}")
    private String projectId;

    private volatile Firestore firestore;

    /**
     * Lazy on purpose. Building the client opens a gRPC channel and resolves Application
     * Default Credentials; doing that eagerly means the service cannot start at all in an
     * environment without GCP credentials, and adds the channel handshake to every Cloud Run
     * cold start — including the ones that only serve a health probe.
     */
    @Bean
    @Lazy
    public Firestore firestore() {
        FirestoreOptions.Builder options = FirestoreOptions.newBuilder();
        if (projectId != null && !projectId.isBlank()) {
            options.setProjectId(projectId.trim());
        }
        this.firestore = options.build().getService();
        log.info("[Firestore] Client initialized for project [{}]", projectId);
        return this.firestore;
    }

    /** Closes the gRPC channel so shutdown is not held open by a live connection. */
    @PreDestroy
    void close() {
        Firestore client = this.firestore;
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (Exception e) {
            log.warn("[Firestore] Failed to close client cleanly", e);
        }
    }
}
