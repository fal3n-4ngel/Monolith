package com.dashboard.api.service;

import com.dashboard.api.ingest.BigQueryInserts;
import com.dashboard.api.ingest.EventClock;
import com.dashboard.api.ingest.PayloadSanitizer;
import com.dashboard.api.dto.DomainEventDto;
import com.dashboard.api.dto.DomainEventResponse;
import com.dashboard.api.events.AppRef;
import com.dashboard.api.events.AppRegistry;
import com.dashboard.api.events.EventRef;
import com.dashboard.api.events.DomainEventWriter;
import com.dashboard.api.notify.DiscordNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Ingest path for domain events — application history, one row per state change. */
@Service
public class DomainEventService {

    private static final Logger log = LoggerFactory.getLogger(DomainEventService.class);

    private final DomainEventWriter writer;
    private final PayloadSanitizer sanitizer;
    private final DiscordNotifier discord;
    private final AppRegistry appRegistry;

    public DomainEventService(DomainEventWriter writer, PayloadSanitizer sanitizer, DiscordNotifier discord,
                              AppRegistry appRegistry) {
        this.writer = writer;
        this.sanitizer = sanitizer;
        this.discord = discord;
        this.appRegistry = appRegistry;
    }

    public DomainEventResponse record(DomainEventDto dto) {
        Optional<AppRef> sourceApp = appRegistry.resolveApp(dto.getSourceApp());
        if (sourceApp.isEmpty()) {
            log.warn("[Event] REJECTED sourceApp={} eventType={}", dto.getSourceApp(), dto.getEventType());
            return DomainEventResponse.rejected(dto.getEventType(), "unknown_source_app");
        }

        Optional<EventRef> resolved = appRegistry.resolveEvent(sourceApp.get().id(), dto.getEventType());
        if (resolved.isEmpty()) {
            log.warn("[Event] REJECTED eventType={} source={}", dto.getEventType(), dto.getSourceApp());
            return DomainEventResponse.rejected(dto.getEventType(), "unknown_event_type");
        }
        EventRef type = resolved.get();

        String eventId = (dto.getEventId() != null && !dto.getEventId().isBlank())
                ? dto.getEventId()
                : UUID.randomUUID().toString();
        Instant receivedAt = Instant.now();
        long occurredAt = EventClock.resolve(dto.getTimestamp(), receivedAt);
        String table = type.tableFor(dto.getSourceApp());

        Map<String, Object> sanitizedPayload = sanitizer.sanitize(dto.getPayload());
        Object environment = sanitizedPayload.get("environment");
        String envStr = environment == null ? null : environment.toString();
        String envClean = envStr == null ? "unknown" : envStr.trim().toLowerCase();

        String logTag;
        if ("production".equals(envClean) || "prod".equals(envClean)) {
            logTag = "[Event]";
        } else if ("uat".equals(envClean)) {
            logTag = "[Event:UAT]";
        } else {
            logTag = "[Event:TEST]";
        }

        log.info("{} ACCEPTED eventType={} eventId={} source={} env={} table={}", logTag, type.name(), eventId, dto.getSourceApp(), envStr == null ? "unknown" : envStr, table);

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

        String payload = BigQueryInserts.toJsonColumn(sanitizedPayload);
        if (payload != null) {
            row.put("payload", payload);
        }

        writer.write(table, eventId, row);

        discord.notifyDomainEvent(type.name(), dto.getSourceApp(), table, envStr, eventId);

        return new DomainEventResponse("ACCEPTED", eventId, type.name(), table, occurredAt, null);
    }
}
