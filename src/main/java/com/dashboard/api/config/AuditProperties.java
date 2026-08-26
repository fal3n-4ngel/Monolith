package com.dashboard.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed configuration for the ingest pipeline. Bound from {@code AUDIT_*} env vars (prefix kept
 * for continuity with already-deployed config, though the service is domain-event ingest now).
 */
@ConfigurationProperties(prefix = "audit")
public record AuditProperties(

        /* ---- Payload hardening (bounds BigQuery JSON column size + insert cost) ---- */

        /** Max key/value pairs retained per free-form map (payload). */
        @DefaultValue("32") int maxMapEntries,

        /** Max characters retained per string value before truncation. */
        @DefaultValue("512") int maxValueLength,

        /** Max nesting depth for free-form maps. */
        @DefaultValue("4") int maxMapDepth,

        /* ---- Ingest throttling ---- */

        /** Per-IP postback budget per minute, enforced per Cloud Run instance. Zero disables. */
        @DefaultValue("120") int rateLimitPerMinute,

        /* ---- BigQuery ---- */

        /** Escape hatch: disable BigQuery writes without a redeploy if the pipeline misbehaves. */
        @DefaultValue("true") boolean bigqueryEnabled,

        @DefaultValue("events") String bigqueryDomainDataset,

        /** Dataset location. Must match at dataset-creation time; changing it later requires a new dataset. */
        @DefaultValue("US") String bigqueryLocation
) {
}
