package com.dashboard.api.reports;

import com.dashboard.api.config.AuditProperties;
import com.dashboard.api.config.ReportProperties;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.FieldValue;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.JobId;
import com.google.cloud.bigquery.LegacySQLTypeName;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs a report's parameterised SQL. The SQL is admin-authored and already validated read-only;
 * this binds the caller's parameter values and applies the {@link ReportProperties} cost guards.
 */
@Component
public class BigQueryReportRunner {

    private static final Logger log = LoggerFactory.getLogger(BigQueryReportRunner.class);
    private static final String ALL_EVENTS_MACRO = "{{all_events}}";

    private final ObjectProvider<BigQuery> bigQueryProvider;
    private final ReportProperties reportProps;
    private final AuditProperties auditProps;
    private final String projectId;

    public BigQueryReportRunner(ObjectProvider<BigQuery> bigQueryProvider,
                                ReportProperties reportProps,
                                AuditProperties auditProps,
                                @Value("${dashboard.gcp.project-id:}") String projectId) {
        this.bigQueryProvider = bigQueryProvider;
        this.reportProps = reportProps;
        this.auditProps = auditProps;
        this.projectId = projectId == null ? "" : projectId.trim();
    }

    /** Column names and up to {@code reports.max-rows} rows; {@code truncated} if the query had more. */
    public record Result(List<String> columns, List<List<String>> rows, boolean truncated) {
    }

    public Result run(String catalogSql, Map<String, QueryParameterValue> params) {
        BigQuery bigQuery = bigQuery();
        if (bigQuery == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "report backend is not configured");
        }

        String sql = catalogSql.replace(ALL_EVENTS_MACRO, allEventsRef());
        int macro = sql.indexOf("{{");
        if (macro >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "report references an unknown table macro: " + sql.substring(macro, Math.min(sql.length(), macro + 24)));
        }

        QueryJobConfiguration.Builder config = QueryJobConfiguration.newBuilder(sql)
                .setUseLegacySql(false)
                .setNamedParameters(params)
                .setUseQueryCache(true);
        if (reportProps.maxBytesBilled() > 0) {
            config.setMaximumBytesBilled(reportProps.maxBytesBilled());
        }

        TableResult result = execute(bigQuery, config.build());

        List<String> columns = new ArrayList<>();
        for (Field field : result.getSchema().getFields()) {
            columns.add(field.getName());
        }

        List<List<String>> rows = new ArrayList<>();
        boolean truncated = false;
        for (FieldValueList row : result.iterateAll()) {
            if (rows.size() >= reportProps.maxRows()) {
                truncated = true;
                break;
            }
            List<String> out = new ArrayList<>(columns.size());
            for (int i = 0; i < columns.size(); i++) {
                out.add(render(result.getSchema().getFields().get(i), row.get(i)));
            }
            rows.add(out);
        }
        return new Result(columns, rows, truncated);
    }

    private TableResult execute(BigQuery bigQuery, QueryJobConfiguration config) {
        try {
            String location = auditProps.bigqueryLocation();
            if (location == null || location.isBlank()) {
                return bigQuery.query(config);
            }
            JobId jobId = JobId.newBuilder()
                    .setLocation(location.trim())
                    .setJob("report_" + UUID.randomUUID().toString().replace("-", ""))
                    .build();
            return bigQuery.query(config, jobId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "report query was interrupted");
        } catch (BigQueryException e) {
            // The SQL is admin-authored, so BigQuery's message is useful to whoever wrote it.
            log.warn("[Reports] BigQuery rejected a report query: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "report query failed: " + e.getMessage());
        } catch (RuntimeException e) {
            log.error("[Reports] report query failed", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "report query failed");
        }
    }

    private String allEventsRef() {
        String dataset = auditProps.bigqueryDomainDataset();
        return projectId.isBlank()
                ? String.format("`%s.all_events`", dataset)
                : String.format("`%s.%s.all_events`", projectId, dataset);
    }

    private static String render(Field field, FieldValue value) {
        if (value == null || value.isNull()) {
            return "";
        }
        if (field.getType() == LegacySQLTypeName.TIMESTAMP) {
            long micros = value.getTimestampValue();
            return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                    Math.floorMod(micros, 1_000_000L) * 1_000L).toString();
        }
        Object raw = value.getValue();
        return raw == null ? "" : raw.toString();
    }

    private BigQuery bigQuery() {
        try {
            return bigQueryProvider.getIfAvailable();
        } catch (RuntimeException e) {
            log.error("[Reports] BigQuery client initialization failed", e);
            return null;
        }
    }
}
