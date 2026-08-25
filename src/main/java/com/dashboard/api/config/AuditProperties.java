package com.dashboard.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Set;

/**
 * Typed configuration for the audit & telemetry pipeline.
 *
 * <p>Every knob that affects cost (write volume, payload size, retention) is
 * bound here so it can be tuned per environment without a redeploy of code.
 */
@ConfigurationProperties(prefix = "audit")
public record AuditProperties(

        /* ---- Origin / theft detection ---- */

        /** Origins permitted to emit telemetry for a known source app. Compared by exact scheme+host+port. */
        @DefaultValue({"https://continuum-home.vercel.app", "http://localhost:3000", "http://localhost:3001"})
        Set<String> authorizedOrigins,

        /** Source apps we own. A claim on one of these from an unauthorized origin is treated as theft. */
        @DefaultValue({"continuum-home"})
        Set<String> knownSourceApps,

        /* ---- Payload hardening (directly caps Firestore doc size + index entries) ---- */

        /** Max key/value pairs retained per free-form map (metadata, context). */
        @DefaultValue("32") int maxMapEntries,

        /** Max characters retained per string value before truncation. */
        @DefaultValue("512") int maxValueLength,

        /** Max nesting depth for free-form maps. Firestore hard-limits at 20. */
        @DefaultValue("4") int maxMapDepth,

        /** Hash client IPs (SHA-256, truncated) instead of storing them verbatim. */
        @DefaultValue("false") boolean hashClientIp,

        /* ---- Retention & query ---- */

        /** TTL applied to every audit doc via the {@code expiresAt} field. Requires a Firestore TTL policy. */
        @DefaultValue("90d") Duration retention,

        @DefaultValue("50") int defaultQueryLimit,
        @DefaultValue("200") int maxQueryLimit,

        /* ---- Ingest throttling ---- */

        /** Per-IP postback budget per minute, enforced per Cloud Run instance. Zero disables. */
        @DefaultValue("120") int rateLimitPerMinute,

        /** Suppress duplicate security alerts for the same origin inside this window. */
        @DefaultValue("15m") Duration alertCooldown
) {
}
