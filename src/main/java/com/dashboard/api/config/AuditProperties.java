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

        /**
         * Per-IP budget per minute across every endpoint (health, swagger, actuator included),
         * enforced per Cloud Run instance. A looser backstop sitting in front of the tighter
         * postback-specific budget above. Zero disables.
         */
        @DefaultValue("300") int globalRateLimitPerMinute,

        /* ---- BigQuery ---- */

        /** Escape hatch: disable BigQuery writes without a redeploy if the pipeline misbehaves. */
        @DefaultValue("true") boolean bigqueryEnabled,

        @DefaultValue("events") String bigqueryDomainDataset,

        /** Dataset location. Must match at dataset-creation time; changing it later requires a new dataset. */
        @DefaultValue("US") String bigqueryLocation,

        /* ---- Audit-log query (read path: GET /audit/logs) ---- */

        /** Rows returned when the caller passes no {@code limit}. */
        @DefaultValue("50") int queryDefaultLimit,

        /** Hard ceiling on {@code limit}, whatever the caller asks for. */
        @DefaultValue("200") int queryMaxLimit,

        /** With no {@code from}, only this many days back are scanned — bounds scan cost. */
        @DefaultValue("30") int queryLookbackDays,

        /** Per-IP and per-credential budget per minute for reads. Zero disables. */
        @DefaultValue("30") int readRateLimitPerMinute,

        /** BigQuery refuses a read estimated to bill more than this many bytes. Zero disables the cap. */
        @DefaultValue("100000000") long queryMaxBytesBilled
) {
}
