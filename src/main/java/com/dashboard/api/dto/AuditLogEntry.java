package com.dashboard.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/** One row of domain-event history; {@code payload} is rehydrated JSON, not an escaped string. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditLogEntry(
        String domain,
        String eventId,
        String sourceApp,
        String userId,
        String eventType,
        String action,
        String entityId,
        Long itemCount,
        String occurredAt,
        String receivedAt,
        JsonNode payload
) {
}
