package com.dashboard.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One row of domain-event history, as returned by {@code GET /audit/logs}. Mirrors the shared
 * BigQuery column set (see {@code DomainEventWriter}), with {@code payload} rehydrated from its
 * stored JSON text back into real JSON rather than an escaped string.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A single domain event from the history")
public record AuditLogEntry(
        @Schema(example = "expenses") String domain,
        @Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") String eventId,
        @Schema(example = "continuum-home") String sourceApp,
        @Schema(description = "Acting user's id as known to the source app", example = "usr_1") String userId,
        @Schema(example = "EXPENSE_CREATED") String eventType,
        @Schema(example = "CREATE") String action,
        @Schema(example = "exp_88213") String entityId,
        @Schema(description = "Rows affected; > 1 for batch operations", example = "1") Long itemCount,
        @Schema(example = "2026-08-30T12:00:00Z") String occurredAt,
        @Schema(example = "2026-08-30T12:00:01Z") String receivedAt,
        @Schema(description = "Sanitized domain-specific detail") JsonNode payload
) {
}
