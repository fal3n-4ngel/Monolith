package com.dashboard.api.audit;

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
 * <p>Exists because of a failure seen in production: Cloud Run scales to zero and idles, and the
 * BigQuery client's pooled HTTPS connection is silently dropped while the instance sleeps. The
 * next insert writes into a dead socket and fails with {@code SocketException: Broken pipe}
 * before a single byte reaches BigQuery. A second attempt opens a fresh connection and succeeds.
 *
 * <p><b>Retrying is only safe because every row carries an {@code insertId}.</b> BigQuery
 * deduplicates on it, so a retry after an ambiguous failure — one where the first attempt may
 * actually have landed — cannot produce a duplicate row.
 *
 * <p>Row-level errors reported in the response are <i>not</i> retried: those are schema or data
 * problems, and a second identical attempt would fail identically.
 */
public final class BigQueryInserts {

    private static final Logger log = LoggerFactory.getLogger(BigQueryInserts.class);
    private static final int MAX_ATTEMPTS = 2;
    private static final long RETRY_DELAY_MS = 100;

    private BigQueryInserts() {
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
