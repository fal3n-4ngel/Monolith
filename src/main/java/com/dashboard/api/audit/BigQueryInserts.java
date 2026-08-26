package com.dashboard.api.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.InsertAllRequest;
import com.google.cloud.bigquery.InsertAllResponse;
import com.google.cloud.bigquery.TableId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Single-row streaming insert with a retry, shared by the audit and domain-event writers.
 *
 * <p>Exists because of a failure seen in production: Cloud Run idles, the BigQuery client's
 * pooled HTTPS connection dies silently, and the next insert fails with
 * {@code SocketException: Broken pipe} before a byte reaches BigQuery. A second attempt on a
 * fresh connection succeeds.
 *
 * <p>Retrying is only safe because every row carries an {@code insertId} — BigQuery dedupes on
 * it, so a retry after an ambiguous failure can't produce a duplicate. Row-level rejections
 * (schema/data problems) are <i>not</i> retried: a second identical attempt fails identically.
 */
public final class BigQueryInserts {

    private static final Logger log = LoggerFactory.getLogger(BigQueryInserts.class);
    private static final int MAX_ATTEMPTS = 2;
    private static final long RETRY_DELAY_MS = 100;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BigQueryInserts() {
    }

    /**
     * Serializes a value for a BigQuery {@code JSON} column. The streaming API rejects a raw
     * nested map there — {@code "This field: <name> is not a record"} — since it reads the
     * nesting as a STRUCT. JSON columns need a serialized string.
     *
     * @return the JSON text, or {@code null} for a null/empty value or an unserializable one.
     */
    public static String toJsonColumn(Object value) {
        if (value == null || (value instanceof Map<?, ?> map && map.isEmpty())) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // Dropping one column beats losing the whole row to a rejection.
            log.warn("[BigQuery] Could not serialize a JSON column; omitting it: {}", e.getMessage());
            return null;
        }
    }

    /** @return true if BigQuery accepted the row. Never throws: ingestion is best-effort. */
    public static boolean insertOne(BigQuery bigQuery, TableId table, String insertId, Map<String, Object> row) {
        InsertAllRequest request = InsertAllRequest.newBuilder(table)
                .addRow(insertId, row)
                .build();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                InsertAllResponse response = bigQuery.insertAll(request);
                if (!response.hasErrors()) {
                    return true;
                }
                response.getInsertErrors().forEach((index, errors) ->
                        log.error("[BigQuery] Row rejected by {} for insertId [{}], row {}: {}",
                                table.getTable(), insertId, index, errors));
                return false;
            } catch (RuntimeException e) {
                if (attempt < MAX_ATTEMPTS) {
                    log.warn("[BigQuery] Insert into {} failed for insertId [{}] ({}); retrying",
                            table.getTable(), insertId, rootMessage(e));
                    sleepBriefly();
                } else {
                    log.error("[BigQuery] Insert into {} failed for insertId [{}] after {} attempts",
                            table.getTable(), insertId, MAX_ATTEMPTS, e);
                }
            }
        }
        return false;
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The useful half of a wrapped client exception is usually the cause, not the wrapper. */
    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
