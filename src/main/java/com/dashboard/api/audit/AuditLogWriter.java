package com.dashboard.api.audit;

import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.BulkWriter;
import com.google.cloud.firestore.BulkWriterOptions;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns all Firestore access for audit logs.
 *
 * <p>Writes go through a {@link BulkWriter} rather than a bare {@code document.set()}:
 *
 * <ul>
 *   <li><b>Reliability.</b> BulkWriter retries failed writes with exponential backoff.
 *       The previous fire-and-forget {@code set()} dropped events on any transient error,
 *       logging the failure and moving on.</li>
 *   <li><b>Coalescing.</b> Writes still in flight when another arrives share a commit,
 *       so concurrent bursts cost fewer gRPC round trips — and on Cloud Run, request
 *       CPU-seconds are the bill.</li>
 * </ul>
 *
 * <p>Firestore charges per <i>document</i> written, so batching does not reduce write cost.
 * Write-cost control lives upstream (rate limiting, payload bounds) and in retention policy.
 */
@Component
public class AuditLogWriter {

    private static final Logger log = LoggerFactory.getLogger(AuditLogWriter.class);
    public static final String COLLECTION = "audit_logs";

    private final ObjectProvider<Firestore> firestoreProvider;
    private final AtomicReference<BulkWriter> writerRef = new AtomicReference<>();

    public AuditLogWriter(ObjectProvider<Firestore> firestoreProvider) {
        this.firestoreProvider = firestoreProvider;
    }

    /** @return true if the event was handed to Firestore, false if no backend is configured. */
    public boolean enqueue(String logId, Map<String, Object> document) {
        Firestore firestore = firestore();
        if (firestore == null) {
            log.warn("[Audit] Firestore unavailable; dropping log [{}]", logId);
            return false;
        }
        try {
            DocumentReference ref = firestore.collection(COLLECTION).document(logId);
            ApiFuture<?> future = writer(firestore).set(ref, document);
            future.addListener(() -> {
                try {
                    future.get();
                    log.debug("[Audit] Persisted log [{}]", logId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    log.error("[Audit] Write failed after retries for log [{}]", logId, e.getCause());
                }
            }, Runnable::run);
            return true;
        } catch (RuntimeException e) {
            log.error("[Audit] Could not enqueue log [{}]", logId, e);
            return false;
        }
    }

    /**
     * Schedules any buffered writes for immediate dispatch. Returns without blocking.
     *
     * <p>BulkWriter otherwise holds a partial batch until it fills, and Cloud Run throttles
     * CPU to near zero between requests — so a purely time-based flush would stall until the
     * next inbound request. The ingest path therefore flushes while it still holds the CPU.
     */
    public void flush() {
        BulkWriter writer = writerRef.get();
        if (writer != null) {
            writer.flush();
        }
    }

    public List<Map<String, Object>> query(String sourceApp, String eventType, Severity severity, int limit) {
        Firestore firestore = firestore();
        if (firestore == null) {
            return List.of();
        }
        try {
            CollectionReference collection = firestore.collection(COLLECTION);
            Query query = collection.orderBy("timestamp", Query.Direction.DESCENDING);

            if (sourceApp != null && !sourceApp.isBlank()) {
                query = query.whereEqualTo("sourceApp", sourceApp);
            }
            if (eventType != null && !eventType.isBlank()) {
                query = query.whereEqualTo("eventType", eventType);
            }
            if (severity != null) {
                query = query.whereEqualTo("severity", severity.name());
            }

            ApiFuture<QuerySnapshot> snapshot = query.limit(limit).get();
            List<Map<String, Object>> results = new ArrayList<>(limit);
            for (QueryDocumentSnapshot document : snapshot.get().getDocuments()) {
                results.add(document.getData());
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (ExecutionException e) {
            // A missing composite index surfaces here as FAILED_PRECONDITION with a
            // console URL in the message — see firestore.indexes.json.
            log.error("[Audit] Query failed (sourceApp={}, eventType={}, severity={})",
                    sourceApp, eventType, severity, e.getCause());
            return List.of();
        }
    }

    private BulkWriter writer(Firestore firestore) {
        BulkWriter existing = writerRef.get();
        if (existing != null) {
            return existing;
        }
        BulkWriter created = firestore.bulkWriter(
                BulkWriterOptions.builder().setThrottlingEnabled(true).build());
        return writerRef.compareAndSet(null, created) ? created : writerRef.get();
    }

    /** Resolved lazily: the Firestore bean is {@code @Lazy} so the app boots without credentials. */
    private Firestore firestore() {
        try {
            return firestoreProvider.getIfAvailable();
        } catch (RuntimeException e) {
            log.error("[Audit] Firestore client initialization failed", e);
            return null;
        }
    }

    /**
     * Cloud Run sends SIGTERM before reclaiming an instance. Draining here is what keeps
     * buffered events from being lost on scale-down.
     */
    @PreDestroy
    void drain() {
        BulkWriter writer = writerRef.getAndSet(null);
        if (writer == null) {
            return;
        }
        try {
            writer.close();
            log.info("[Audit] Drained pending writes on shutdown");
        } catch (Exception e) {
            log.error("[Audit] Failed to drain pending writes on shutdown", e);
        }
    }
}
