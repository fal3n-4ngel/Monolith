package com.dashboard.api.query;

import com.dashboard.api.config.AuditProperties;
import com.dashboard.api.dto.AuditLogEntry;
import com.dashboard.api.events.SourceApp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.FieldValue;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.JobId;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.QueryParameterValue;
import com.google.cloud.bigquery.TableResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs the audit-log read as a single parameterised BigQuery query against the {@code all_events}
 * view.
 *
 * <p>Every filter is a bound named parameter, never string-concatenated — the same instinct as
 * the ingest path resolving tables from an enum instead of a caller string. The
 * {@code source_app} predicate is set from the authenticated credential upstream
 * ({@link AuditLogService}); this class binds whatever scope it is handed and nothing more.
 */
@Component
public class BigQueryAuditLogRepository {

    private static final Logger log = LoggerFactory.getLogger(BigQueryAuditLogRepository.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ObjectProvider<BigQuery> bigQueryProvider;
    private final AuditProperties props;
    private final String projectId;

    public BigQueryAuditLogRepository(ObjectProvider<BigQuery> bigQueryProvider,
                                     AuditProperties props,
                                     @Value("${dashboard.gcp.project-id:}") String projectId) {
        this.bigQueryProvider = bigQueryProvider;
        this.props = props;
        this.projectId = projectId == null ? "" : projectId.trim();
    }

    /**
     * Already-scoped, already-validated read criteria. {@code sourceApp} empty means "every app"
     * — only reachable for a cross-app credential.
     */
    public record Criteria(
            Optional<SourceApp> sourceApp,
            String userId,
            String domain,
            String eventType,
            Instant from,
            Instant before,
            int limit
    ) {
    }

    public List<AuditLogEntry> search(Criteria criteria) {
        if (!props.bigqueryEnabled()) {
            throw new AuditBackendUnavailableException("audit reads are disabled (audit.bigquery-enabled=false)");
        }
        BigQuery bigQuery = bigQuery();
        if (bigQuery == null) {
            throw new AuditBackendUnavailableException("audit query backend is not configured");
        }

        Map<String, QueryParameterValue> params = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder()
                .append("SELECT domain, event_id, source_app, local_user_id, event_type, action, entity_id, ")
                .append("item_count, occurred_at, received_at, TO_JSON_STRING(payload) AS payload_json FROM ")
                .append(viewRef())
                .append(" WHERE TRUE");

        criteria.sourceApp().ifPresent(app -> {
            sql.append(" AND source_app = @sourceApp");
            params.put("sourceApp", QueryParameterValue.string(app.appId()));
        });
        if (criteria.userId() != null) {
            sql.append(" AND local_user_id = @userId");
            params.put("userId", QueryParameterValue.string(criteria.userId()));
        }
        if (criteria.domain() != null) {
            sql.append(" AND domain = @domain");
            params.put("domain", QueryParameterValue.string(criteria.domain()));
        }
        if (criteria.eventType() != null) {
            sql.append(" AND event_type = @eventType");
            params.put("eventType", QueryParameterValue.string(criteria.eventType()));
        }
        if (criteria.from() != null) {
            sql.append(" AND occurred_at >= @fromTs");
            params.put("fromTs", QueryParameterValue.timestamp(micros(criteria.from())));
        }
        if (criteria.before() != null) {
            sql.append(" AND occurred_at < @beforeTs");
            params.put("beforeTs", QueryParameterValue.timestamp(micros(criteria.before())));
        }
        sql.append(" ORDER BY occurred_at DESC LIMIT @rowLimit");
        params.put("rowLimit", QueryParameterValue.int64((long) criteria.limit()));

        QueryJobConfiguration.Builder config = QueryJobConfiguration.newBuilder(sql.toString())
                .setUseLegacySql(false)
                .setNamedParameters(params)
                .setUseQueryCache(true);
        if (props.queryMaxBytesBilled() > 0) {
            config.setMaximumBytesBilled(props.queryMaxBytesBilled());
        }

        TableResult result = run(bigQuery, config.build());

        List<AuditLogEntry> rows = new ArrayList<>();
        for (FieldValueList row : result.iterateAll()) {
            rows.add(new AuditLogEntry(
                    string(row, "domain"),
                    string(row, "event_id"),
                    string(row, "source_app"),
                    string(row, "local_user_id"),
                    string(row, "event_type"),
                    string(row, "action"),
                    string(row, "entity_id"),
                    row.get("item_count").isNull() ? null : row.get("item_count").getLongValue(),
                    isoTimestamp(row, "occurred_at"),
                    isoTimestamp(row, "received_at"),
                    parseJson(string(row, "payload_json"))));
        }
        return rows;
    }

    private TableResult run(BigQuery bigQuery, QueryJobConfiguration config) {
        try {
            String location = props.bigqueryLocation();
            if (location == null || location.isBlank()) {
                return bigQuery.query(config);
            }
            // An explicit job id is required to pin the job's location; give it a real name so
            // the client never has to invent one.
            JobId jobId = JobId.newBuilder()
                    .setLocation(location.trim())
                    .setJob("audit_read_" + java.util.UUID.randomUUID().toString().replace("-", ""))
                    .build();
            return bigQuery.query(config, jobId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuditBackendUnavailableException("audit query was interrupted");
        } catch (BigQueryException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("bytes billed")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "query would scan too much data — narrow the time range or add filters");
            }
            log.error("[AuditLog] BigQuery read failed", e);
            throw new AuditBackendUnavailableException("audit query backend error");
        } catch (RuntimeException e) {
            log.error("[AuditLog] audit read failed", e);
            throw new AuditBackendUnavailableException("audit query backend error");
        }
    }

    private String viewRef() {
        String dataset = props.bigqueryDomainDataset();
        return projectId.isBlank()
                ? String.format("`%s.all_events`", dataset)
                : String.format("`%s.%s.all_events`", projectId, dataset);
    }

    private static long micros(Instant instant) {
        return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000L;
    }

    private static String string(FieldValueList row, String field) {
        FieldValue value = row.get(field);
        return value.isNull() ? null : value.getStringValue();
    }

    private static String isoTimestamp(FieldValueList row, String field) {
        FieldValue value = row.get(field);
        if (value.isNull()) {
            return null;
        }
        long micros = value.getTimestampValue();
        return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                Math.floorMod(micros, 1_000_000L) * 1_000L).toString();
    }

    private static JsonNode parseJson(String raw) {
        if (raw == null || raw.isBlank() || "null".equals(raw)) {
            return null;
        }
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            log.warn("[AuditLog] could not parse stored payload JSON; returning null: {}", e.getMessage());
            return null;
        }
    }

    private BigQuery bigQuery() {
        try {
            return bigQueryProvider.getIfAvailable();
        } catch (RuntimeException e) {
            log.error("[AuditLog] BigQuery client initialization failed", e);
            return null;
        }
    }
}
