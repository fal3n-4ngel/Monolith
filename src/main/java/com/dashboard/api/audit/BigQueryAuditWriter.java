package com.dashboard.api.audit;

import com.dashboard.api.config.AuditProperties;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.InsertAllRequest;
import com.google.cloud.bigquery.InsertAllResponse;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.QueryParameterValue;
import com.google.cloud.bigquery.TableId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Second, unbounded-retention sink for audit events — additive to {@link AuditLogWriter}, never
 * a replacement. Firestore stays the short-TTL operational store behind {@code /api/v1/audit/logs};
 * this writer feeds {@code audit_events} (append-only fact table) and {@code identity_links}
 * (upserted, the actual cross-app "fact linking" — one row per app-local user ever seen with a
 * verified email). See {@code infra/setup-bigquery.sh} for the DDL both tables depend on.
 *
 * <p>Uses the plain streaming {@code insertAll} rather than the Storage Write API: per-row
 * {@code insertId}-based dedup (what {@code eventId} exists for) is a first-class feature of
 * {@code insertAll}, whereas the Storage Write API's default stream has no equivalent — only its
 * committed/pending stream types do, at a complexity and dependency cost this volume doesn't
 * justify. Legacy streaming pricing is negligible at personal-project scale.
 */
@Component
public class BigQueryAuditWriter {

    private static final Logger log = LoggerFactory.getLogger(BigQueryAuditWriter.class);
    private static final String EVENTS_TABLE = "audit_events";
    private static final String IDENTITY_TABLE = "identity_links";
    private static final int MAX_NAME_LENGTH = 256;

    private final ObjectProvider<BigQuery> bigQueryProvider;
    private final AuditProperties props;

    public BigQueryAuditWriter(ObjectProvider<BigQuery> bigQueryProvider, AuditProperties props) {
        this.bigQueryProvider = bigQueryProvider;
        this.props = props;
    }

    /**
     * Fire-and-forget: runs off the request thread and never lets a BigQuery hiccup slow or
     * fail the postback response, matching every other downstream in this service.
     */
    @Async("bigqueryExecutor")
    public void enqueue(Map<String, Object> document) {
        if (!props.bigqueryEnabled()) {
            return;
        }
        BigQuery bigQuery = bigQuery();
        if (bigQuery == null) {
            return;
        }

        String eventId = firstNonBlank((String) document.get("eventId"), (String) document.get("logId"));
        try {
            insertEvent(bigQuery, eventId, document);
        } catch (RuntimeException e) {
            log.error("[BigQueryAudit] Failed to insert event [{}]", eventId, e);
        }

        String email = extractEmail(document);
        if (email != null) {
            try {
                upsertIdentity(bigQuery, document, email);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.error("[BigQueryAudit] Failed to upsert identity for an event on [{}]", document.get("sourceApp"), e);
            }
        }
    }

    private void insertEvent(BigQuery bigQuery, String eventId, Map<String, Object> document) {
        TableId table = TableId.of(props.bigqueryDataset(), EVENTS_TABLE);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("event_id", eventId);
        row.put("log_id", document.get("logId"));
        row.put("source_app", document.get("sourceApp"));
        row.put("event_type", document.get("eventType"));
        row.put("severity", document.get("severity"));
        row.put("user_id", document.get("userId"));
        row.put("event_timestamp", toBqTimestamp(document.get("timestamp")));
        row.put("received_at", toBqTimestamp(document.get("receivedAt")));
        row.put("is_unauthorized", document.getOrDefault("isUnauthorized", false));
        row.put("resolved_origin", document.get("resolvedOrigin"));
        row.put("origin_source", document.get("originSource"));

        if (document.get("observed") instanceof Map<?, ?> observed) {
            row.put("observed_origin", observed.get("origin"));
            row.put("observed_referer", observed.get("referer"));
            row.put("observed_user_agent", observed.get("userAgent"));
            row.put("observed_client_ip", observed.get("clientIp"));
        }

        // context/metadata are native BigQuery JSON columns — pass the sanitized maps through
        // as-is, same shape they're stored in Firestore, rather than a fixed set of columns.
        row.put("context", document.get("context"));
        if (document.get("metadata") != null) {
            row.put("metadata", document.get("metadata"));
        }

        // insertId = eventId gives best-effort dedup: a retried or keepalive-resent postback
        // with the same eventId lands once, not twice.
        InsertAllRequest request = InsertAllRequest.newBuilder(table)
                .addRow(eventId, row)
                .build();

        InsertAllResponse response = bigQuery.insertAll(request);
        if (response.hasErrors()) {
            response.getInsertErrors().forEach((index, errors) ->
                    log.error("[BigQueryAudit] Insert error for event [{}], row {}: {}", eventId, index, errors));
        }
    }

    /** MERGE-based upsert: last-write-wins per (source_app, local_user_id). Never used for matching by name. */
    private void upsertIdentity(BigQuery bigQuery, Map<String, Object> document, String email) throws InterruptedException {
        String sourceApp = String.valueOf(document.get("sourceApp"));
        Object userIdObj = document.get("userId");
        String localUserId = userIdObj == null ? null : String.valueOf(userIdObj);
        if (localUserId == null || localUserId.isBlank() || "anonymous".equals(localUserId)) {
            return; // nothing app-local to key the link on
        }
        String displayName = extractDisplayName(document);
        long nowMicros = Instant.now().toEpochMilli() * 1000L;

        String sql = """
                MERGE `%s.%s` T
                USING (SELECT
                    @sourceApp AS source_app, @localUserId AS local_user_id,
                    @email AS email, @displayName AS display_name, @now AS ts
                ) S
                ON T.source_app = S.source_app AND T.local_user_id = S.local_user_id
                WHEN MATCHED THEN UPDATE SET
                    email = S.email,
                    display_name = COALESCE(S.display_name, T.display_name),
                    last_seen = S.ts
                WHEN NOT MATCHED THEN
                    INSERT (source_app, local_user_id, email, display_name, first_seen, last_seen)
                    VALUES (S.source_app, S.local_user_id, S.email, S.display_name, S.ts, S.ts)
                """.formatted(props.bigqueryDataset(), IDENTITY_TABLE);

        QueryJobConfiguration config = QueryJobConfiguration.newBuilder(sql)
                .addNamedParameter("sourceApp", QueryParameterValue.string(sourceApp))
                .addNamedParameter("localUserId", QueryParameterValue.string(localUserId))
                .addNamedParameter("email", QueryParameterValue.string(email))
                .addNamedParameter("displayName", QueryParameterValue.string(displayName))
                .addNamedParameter("now", QueryParameterValue.timestamp(nowMicros))
                .build();

        // Synchronous, but this method only ever runs on bigqueryExecutor — never the request thread.
        bigQuery.query(config);
    }

    private static String extractEmail(Map<String, Object> document) {
        if (!(document.get("metadata") instanceof Map<?, ?> metadata)) {
            return null;
        }
        if (!(metadata.get("email") instanceof String email) || email.isBlank()) {
            return null;
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("@") ? normalized : null;
    }

    private static String extractDisplayName(Map<String, Object> document) {
        if (!(document.get("metadata") instanceof Map<?, ?> metadata)) {
            return null;
        }
        if (!(metadata.get("name") instanceof String name) || name.isBlank()) {
            return null;
        }
        String trimmed = name.trim();
        return trimmed.length() > MAX_NAME_LENGTH ? trimmed.substring(0, MAX_NAME_LENGTH) : trimmed;
    }

    private static String toBqTimestamp(Object epochMillis) {
        if (!(epochMillis instanceof Number number)) {
            return null;
        }
        return Instant.ofEpochMilli(number.longValue()).toString();
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    /** Resolved lazily: the BigQuery bean is {@code @Lazy} so the app boots without credentials. */
    private BigQuery bigQuery() {
        try {
            return bigQueryProvider.getIfAvailable();
        } catch (RuntimeException e) {
            log.error("[BigQueryAudit] BigQuery client initialization failed", e);
            return null;
        }
    }
}
