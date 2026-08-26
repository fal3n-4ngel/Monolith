package com.dashboard.api.service;

import com.dashboard.api.audit.EventClock;
import com.dashboard.api.audit.PayloadSanitizer;
import com.dashboard.api.dto.DomainEventDto;
import com.dashboard.api.dto.DomainEventResponse;
import com.dashboard.api.events.DomainEventType;
import com.dashboard.api.events.DomainEventWriter;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Ingest path for domain events.
 *
 * <p>Separate from {@link AuditPostbackService} on purpose. Audit events are security facts
 * about sessions and access, with a retention policy and an unauthenticated browser-reachable
 * endpoint. Domain events are application history — server-to-server only, authenticated, kept
 * indefinitely. Merging the two would mean the weaker guarantees of one applying to both.
 */
@Service
public class DomainEventService {

    private final DomainEventWriter writer;
    private final PayloadSanitizer sanitizer;

    public DomainEventService(DomainEventWriter writer, PayloadSanitizer sanitizer) {
        this.writer = writer;
        this.sanitizer = sanitizer;
    }

    public DomainEventResponse record(DomainEventDto dto) {
        Optional<DomainEventType> resolved = DomainEventType.parse(dto.getEventType());
        if (resolved.isEmpty()) {
            return DomainEventResponse.rejected(dto.getEventType(), "unknown_event_type");
        }
        DomainEventType type = resolved.get();

        String eventId = (dto.getEventId() != null && !dto.getEventId().isBlank())
                ? dto.getEventId()
                : UUID.randomUUID().toString();
        Instant receivedAt = Instant.now();
        long occurredAt = EventClock.resolve(dto.getTimestamp(), receivedAt);
        String table = type.tableFor(dto.getSourceApp());

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("event_id", eventId);
        row.put("source_app", dto.getSourceApp());
        row.put("local_user_id", dto.getUserId());
        row.put("event_type", type.name());
        row.put("action", type.action().name());
        row.put("entity_id", dto.getEntityId());
        row.put("item_count", dto.getItemCount() == null ? 1 : dto.getItemCount());
        row.put("occurred_at", Instant.ofEpochMilli(occurredAt).toString());
        row.put("received_at", receivedAt.toString());

        Map<String, Object> payload = sanitizer.sanitize(dto.getPayload());
        if (!payload.isEmpty()) {
            row.put("payload", payload);
        }

        writer.write(table, eventId, row);

        return new DomainEventResponse("ACCEPTED", eventId, type.name(), table, occurredAt, null);
    }
}
