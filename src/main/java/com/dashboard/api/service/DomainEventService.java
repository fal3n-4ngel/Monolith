package com.dashboard.api.service;

import com.dashboard.api.ingest.BigQueryInserts;
import com.dashboard.api.ingest.EventClock;
import com.dashboard.api.ingest.PayloadSanitizer;
import com.dashboard.api.dto.DomainEventDto;
import com.dashboard.api.dto.DomainEventResponse;
import com.dashboard.api.events.DomainEventType;
import com.dashboard.api.events.DomainEventWriter;
import com.dashboard.api.notify.DiscordNotifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Ingest path for domain events — application history, one row per state change. */
@Service
public class DomainEventService {

    private final DomainEventWriter writer;
    private final PayloadSanitizer sanitizer;
    private final DiscordNotifier discord;

    public DomainEventService(DomainEventWriter writer, PayloadSanitizer sanitizer, DiscordNotifier discord) {
        this.writer = writer;
        this.sanitizer = sanitizer;
        this.discord = discord;
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

        Map<String, Object> sanitizedPayload = sanitizer.sanitize(dto.getPayload());

        // payload is a BigQuery JSON column: it takes serialized JSON text over the streaming
        // API, not a nested map (which is read as a STRUCT and rejects the row).
        String payload = BigQueryInserts.toJsonColumn(sanitizedPayload);
        if (payload != null) {
            row.put("payload", payload);
        }

        writer.write(table, eventId, row);

        Object environment = sanitizedPayload.get("environment");
        discord.notifyDomainEvent(type.name(), dto.getSourceApp(), table,
                environment == null ? null : environment.toString(), eventId);

        return new DomainEventResponse("ACCEPTED", eventId, type.name(), table, occurredAt, null);
    }
}
