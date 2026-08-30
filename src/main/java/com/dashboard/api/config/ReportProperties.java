package com.dashboard.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Cost guards and catalog location for {@code /api/v1/reports}. */
@ConfigurationProperties(prefix = "reports")
public record ReportProperties(
        @DefaultValue("classpath:reports.json") String file,
        @DefaultValue("200000000") long maxBytesBilled,
        @DefaultValue("30000") long timeoutMillis,
        @DefaultValue("50000") int maxRows
) {
}
