package com.dashboard.api;

import com.dashboard.api.config.AuditProperties;
import com.dashboard.api.service.DomainEventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the real context with no GCP credentials present, which is also the shape of a
 * cold Cloud Run instance before its first BigQuery call. The BigQuery bean is {@code @Lazy}
 * precisely so startup cannot fail on missing credentials.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "API_KEY=test-key",
        "audit.rate-limit-per-minute=5"
})
class ApplicationContextTest {

    @Autowired
    private AuditProperties props;

    @Autowired
    private DomainEventService service;

    @Test
    void contextStartsWithoutCredentials() {
        assertThat(service).isNotNull();
    }

    @Test
    void ingestPropertiesBindFromConfiguration() {
        assertThat(props.rateLimitPerMinute()).isEqualTo(5);
    }
}
