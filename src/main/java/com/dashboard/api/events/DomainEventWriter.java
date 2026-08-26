package com.dashboard.api.events;

import com.dashboard.api.config.AuditProperties;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.InsertAllRequest;
import com.google.cloud.bigquery.InsertAllResponse;
import com.google.cloud.bigquery.TableId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Writes domain events to their per-app, per-domain BigQuery table.
 *
 * <p>Every table this writes to shares one column set — {@code source_app}, {@code local_user_id},
 * {@code event_type}, {@code action}, {@code entity_id}, {@code item_count}, {@code occurred_at},
 * {@code received_at}, {@code payload}. That uniformity is the whole point: joining any domain
 * table to {@code identity_links} is always the same shape regardless of which domain you start
 * from, so adding a fifth table (or a second app) never invents a new join.
 */
@Component
public class DomainEventWriter {

    private static final Logger log = LoggerFactory.getLogger(DomainEventWriter.class);

    private final ObjectProvider<BigQuery> bigQueryProvider;
    private final AuditProperties props;

    public DomainEventWriter(ObjectProvider<BigQuery> bigQueryProvider, AuditProperties props) {
        this.bigQueryProvider = bigQueryProvider;
        this.props = props;
    }

    /**
     * Fire-and-forget, off the request thread: a domain event is a side record of something that
     * already happened and committed in the source app. Losing one must never be able to slow or
     * fail the caller.
     */
    @Async("bigqueryExecutor")
    public void write(String table, String eventId, Map<String, Object> row) {
        if (!props.bigqueryEnabled()) {
            return;
        }
        BigQuery bigQuery = bigQuery();
        if (bigQuery == null) {
            return;
        }
        try {
            InsertAllResponse response = bigQuery.insertAll(
                    InsertAllRequest.newBuilder(TableId.of(props.bigqueryDomainDataset(), table))
                            // insertId = eventId: a retried postback lands once, not twice.
                            .addRow(eventId, row)
                            .build());

            if (response.hasErrors()) {
                response.getInsertErrors().forEach((index, errors) ->
                        log.error("[DomainEvent] Insert error into [{}] for event [{}], row {}: {}",
                                table, eventId, index, errors));
            }
        } catch (RuntimeException e) {
            log.error("[DomainEvent] Failed to insert event [{}] into [{}]", eventId, table, e);
        }
    }

    /** Resolved lazily: the BigQuery bean is {@code @Lazy} so the app boots without credentials. */
    private BigQuery bigQuery() {
        try {
            return bigQueryProvider.getIfAvailable();
        } catch (RuntimeException e) {
            log.error("[DomainEvent] BigQuery client initialization failed", e);
            return null;
        }
    }
}
