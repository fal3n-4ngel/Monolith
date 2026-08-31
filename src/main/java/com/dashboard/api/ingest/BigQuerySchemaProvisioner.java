package com.dashboard.api.ingest;

import com.dashboard.api.config.AuditProperties;
import com.dashboard.api.events.AppRegistry;
import com.dashboard.api.events.EventRef;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.DatasetId;
import com.google.cloud.bigquery.DatasetInfo;
import com.google.cloud.bigquery.JobId;
import com.google.cloud.bigquery.QueryJobConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Brings BigQuery in line with {@code apps.json} on every startup: creates the dataset and any
 * missing per-(app, domain) table, then rebuilds {@code all_events} to union exactly the tables the
 * registry declares. Idempotent and additive — {@code CREATE TABLE IF NOT EXISTS} /
 * {@code CREATE OR REPLACE VIEW} — so the deploy that ships a new app also provisions its storage,
 * <p>Fail-soft: a provisioning error is logged and swallowed, exactly like a dropped insert. It
 * never blocks readiness or fails a deploy.
 */
@Component
public class BigQuerySchemaProvisioner {

    private static final Logger log = LoggerFactory.getLogger(BigQuerySchemaProvisioner.class);

    private static final String COLUMNS = """
            event_id STRING, source_app STRING, local_user_id STRING, event_type STRING,
            action STRING, entity_id STRING, item_count INT64, occurred_at TIMESTAMP,
            received_at TIMESTAMP, payload JSON""";

    private final AppRegistry appRegistry;
    private final ObjectProvider<BigQuery> bigQueryProvider;
    private final AuditProperties props;
    private final boolean enabled;
    private final String projectId;

    public BigQuerySchemaProvisioner(AppRegistry appRegistry,
                                     ObjectProvider<BigQuery> bigQueryProvider,
                                     AuditProperties props,
                                     @Value("${monolith.provision-bigquery:true}") boolean enabled,
                                     @Value("${dashboard.gcp.project-id:}") String projectId) {
        this.appRegistry = appRegistry;
        this.bigQueryProvider = bigQueryProvider;
        this.props = props;
        this.enabled = enabled;
        this.projectId = projectId == null ? "" : projectId.trim();
    }

    @Async("bigqueryExecutor")
    @EventListener(ApplicationReadyEvent.class)
    public void provision() {
        if (!enabled || !props.bigqueryEnabled()) {
            log.info("[Provision] Skipped (monolith.provision-bigquery={}, audit.bigquery-enabled={})",
                    enabled, props.bigqueryEnabled());
            return;
        }
        BigQuery bigQuery;
        try {
            bigQuery = bigQueryProvider.getIfAvailable();
        } catch (RuntimeException e) {
            log.warn("[Provision] BigQuery client unavailable; skipping schema provisioning: {}", e.getMessage());
            return;
        }
        if (bigQuery == null) {
            log.info("[Provision] No BigQuery credentials; skipping schema provisioning");
            return;
        }

        String dataset = props.bigqueryDomainDataset();
        try {
            ensureDataset(bigQuery, dataset);

            List<String> missing = new ArrayList<>();
            for (String table : tables().keySet()) {
                if (!tableExists(bigQuery, dataset, table)) {
                    missing.add(table);
                }
            }
            boolean viewExists = tableExists(bigQuery, dataset, "all_events");

            if (missing.isEmpty() && viewExists) {
                log.info("[Provision] Schema already in sync ({} table(s), all_events present)", tables().size());
                return;
            }
            for (String table : missing) {
                createTable(bigQuery, dataset, table);
            }
            rebuildAllEventsView(bigQuery, dataset);
            log.info("[Provision] Schema synced ({} table(s) created, all_events rebuilt over {})",
                    missing.size(), tables().size());
        } catch (RuntimeException e) {
            log.error("[Provision] Schema provisioning failed; ingest for any missing table will drop until the next boot", e);
        }
    }

    /** Ordered {@code table -> domain} for every (app, domain) the registry declares. */
    private Map<String, String> tables() {
        Map<String, String> tables = new java.util.LinkedHashMap<>();
        for (AppRegistry.AppDefinition app : appRegistry.apps()) {
            for (Map.Entry<String, List<EventRef>> domain : app.eventsByDomain().entrySet()) {
                tables.put(EventRef.normalize(app.id()) + "_" + domain.getKey(), domain.getKey());
            }
        }
        return tables;
    }

    private void ensureDataset(BigQuery bigQuery, String dataset) {
        if (bigQuery.getDataset(DatasetId.of(dataset)) != null) {
            return;
        }
        DatasetInfo.Builder info = DatasetInfo.newBuilder(dataset);
        String location = props.bigqueryLocation();
        if (location != null && !location.isBlank()) {
            info.setLocation(location.trim());
        }
        try {
            bigQuery.create(info.build());
            log.info("[Provision] Created dataset '{}'", dataset);
        } catch (RuntimeException e) {
            // A concurrent boot may have won the race; a real failure surfaces on the DDL below.
            log.info("[Provision] Dataset '{}' create skipped: {}", dataset, e.getMessage());
        }
    }

    private void createTable(BigQuery bigQuery, String dataset, String table) {
        runDdl(bigQuery, "CREATE TABLE IF NOT EXISTS " + ref(dataset, table) + " (" + COLUMNS + ") "
                + "PARTITION BY TIMESTAMP_TRUNC(occurred_at, DAY) "
                + "CLUSTER BY local_user_id, event_type");
    }

    private boolean tableExists(BigQuery bigQuery, String dataset, String table) {
        try {
            return bigQuery.getTable(dataset, table) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void rebuildAllEventsView(BigQuery bigQuery, String dataset) {
        List<String> selects = new ArrayList<>();
        for (Map.Entry<String, String> table : tables().entrySet()) {
            selects.add("SELECT '" + table.getValue() + "' AS domain, * FROM " + ref(dataset, table.getKey()));
        }
        if (selects.isEmpty()) {
            log.warn("[Provision] No tables declared; leaving all_events untouched");
            return;
        }
        String ddl = "CREATE OR REPLACE VIEW " + ref(dataset, "all_events") + " AS "
                + String.join("\nUNION ALL\n", selects);
        runDdl(bigQuery, ddl);
    }

    private void runDdl(BigQuery bigQuery, String ddl) {
        QueryJobConfiguration config = QueryJobConfiguration.newBuilder(ddl).setUseLegacySql(false).build();
        try {
            String location = props.bigqueryLocation();
            if (location == null || location.isBlank()) {
                bigQuery.query(config);
            } else {
                JobId jobId = JobId.newBuilder()
                        .setLocation(location.trim())
                        .setJob("provision_" + UUID.randomUUID().toString().replace("-", ""))
                        .build();
                bigQuery.query(config, jobId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("provisioning query interrupted", e);
        }
    }

    private String ref(String dataset, String object) {
        return projectId.isBlank()
                ? String.format("`%s.%s`", dataset, object)
                : String.format("`%s.%s.%s`", projectId, dataset, object);
    }
}
