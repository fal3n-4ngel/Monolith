package com.dashboard.api.service;

import com.dashboard.api.audit.AuditLogWriter;
import com.dashboard.api.audit.BigQueryAuditWriter;
import com.dashboard.api.audit.EventClock;
import com.dashboard.api.audit.OriginValidator;
import com.dashboard.api.audit.PayloadSanitizer;
import com.dashboard.api.audit.Severity;
import com.dashboard.api.config.AuditProperties;
import com.dashboard.api.dto.AuditPostbackDto;
import com.dashboard.api.dto.AuditPostbackResponse;
import com.google.cloud.Timestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ingest path for audit and telemetry postbacks.
 *
 * <p>The request thread does validation and nothing else that can block: the Firestore
 * write is queued, and the Discord alert is dispatched on a separate executor. A slow
 * or hung downstream can no longer hold a Cloud Run request slot open.
 */
@Service
public class AuditPostbackService {

    private static final Logger log = LoggerFactory.getLogger(AuditPostbackService.class);

    private final AuditLogWriter writer;
    private final BigQueryAuditWriter bigQueryWriter;
    private final OriginValidator originValidator;
    private final PayloadSanitizer sanitizer;
    private final DiscordAlertService discordAlertService;
    private final AuditProperties props;

    public AuditPostbackService(AuditLogWriter writer,
                                BigQueryAuditWriter bigQueryWriter,
                                OriginValidator originValidator,
                                PayloadSanitizer sanitizer,
                                DiscordAlertService discordAlertService,
                                AuditProperties props) {
        this.writer = writer;
        this.bigQueryWriter = bigQueryWriter;
        this.originValidator = originValidator;
        this.sanitizer = sanitizer;
        this.discordAlertService = discordAlertService;
        this.props = props;
    }

    /** Everything the transport layer observed about the caller, as opposed to what it claimed. */
    public record RequestContext(String origin, String referer, String userAgent, String clientIp) {
        public static final RequestContext EMPTY = new RequestContext(null, null, null, null);
    }

    public AuditPostbackResponse record(AuditPostbackDto dto, RequestContext request) {
        String logId = UUID.randomUUID().toString();
        Instant receivedAt = Instant.now();
        long eventTimestamp = EventClock.resolve(dto.getTimestamp(), receivedAt);
        Severity severity = Severity.parse(dto.getSeverity());

        Map<String, Object> context = sanitizer.sanitize(dto.getContext());
        Map<String, Object> observed = observedContext(request);

        OriginValidator.Verdict verdict = originValidator.evaluate(
                dto.getSourceApp(), request.origin(), request.referer(), context);

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("logId", logId);
        if (dto.getEventId() != null && !dto.getEventId().isBlank()) {
            document.put("eventId", dto.getEventId());
        }
        document.put("sourceApp", dto.getSourceApp());
        document.put("eventType", dto.getEventType());
        document.put("severity", severity.name());
        document.put("userId", dto.getUserId() != null && !dto.getUserId().isBlank() ? dto.getUserId() : "anonymous");
        document.put("timestamp", eventTimestamp);
        document.put("receivedAt", receivedAt.toEpochMilli());
        document.put("expiresAt", Timestamp.ofTimeSecondsAndNanos(
                receivedAt.plus(props.retention()).getEpochSecond(), 0));
        document.put("context", context);
        document.put("observed", observed);

        Map<String, Object> metadata = sanitizer.sanitize(dto.getMetadata());
        if (!metadata.isEmpty()) {
            document.put("metadata", metadata);
        }
        if (verdict.origin() != null) {
            document.put("resolvedOrigin", verdict.origin());
            document.put("originSource", verdict.source().name());
        }
        if (verdict.stolenBrand()) {
            document.put("isUnauthorized", true);
            document.put("securityAlert", AuditPostbackResponse.ALERT_UNAUTHORIZED_ORIGIN);
        }

        writer.enqueue(logId, document);
        // Dispatch while the request still holds an unthrottled CPU (see AuditLogWriter#flush).
        writer.flush();
        bigQueryWriter.enqueue(document);

        if (verdict.stolenBrand()) {
            log.warn("[Audit] Unauthorized origin [{}] via {} claiming sourceApp [{}] (log {})",
                    verdict.origin(), verdict.source(), dto.getSourceApp(), logId);
            discordAlertService.alertUnauthorizedOrigin(new DiscordAlertService.OriginAlert(
                    dto.getSourceApp(), verdict.origin(), verdict.source().name(),
                    (String) observed.get("clientIp"), dto.getEventType(), request.userAgent(), logId));
        }

        return new AuditPostbackResponse(
                "ACCEPTED", logId, dto.getSourceApp(), dto.getEventType(), eventTimestamp,
                verdict.stolenBrand() ? AuditPostbackResponse.ALERT_UNAUTHORIZED_ORIGIN : null);
    }

    public List<Map<String, Object>> queryAuditLogs(String sourceApp, String eventType, String severity, Integer limit) {
        int effectiveLimit = (limit == null || limit <= 0)
                ? props.defaultQueryLimit()
                : Math.min(limit, props.maxQueryLimit());
        Severity parsed = (severity == null || severity.isBlank()) ? null : Severity.parse(severity);
        return writer.query(sourceApp, eventType, parsed, effectiveLimit);
    }

    /**
     * Backstop for anything left buffered by a request that failed after enqueueing.
     * Cloud Run's between-request CPU throttling makes this best-effort, which is why the
     * ingest path does not depend on it.
     */
    @Scheduled(fixedDelayString = "${audit.flush-interval-ms:5000}")
    void flushPendingWrites() {
        writer.flush();
    }

    /** Server-observed transport facts, kept separate from caller-supplied {@code context}. */
    private Map<String, Object> observedContext(RequestContext request) {
        Map<String, Object> observed = new LinkedHashMap<>();
        putIfPresent(observed, "origin", request.origin());
        putIfPresent(observed, "referer", request.referer());
        putIfPresent(observed, "userAgent", request.userAgent());
        putIfPresent(observed, "clientIp", sanitizer.prepareClientIp(request.clientIp()));
        return observed;
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value.length() > 512 ? value.substring(0, 512) : value);
        }
    }

}
