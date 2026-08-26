package com.dashboard.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Acknowledgement for a domain event.
 *
 * <p>{@code ACCEPTED} means validated, routed, and queued — the BigQuery insert happens off the
 * request thread, so this is not a durability guarantee. {@code REJECTED} means the
 * {@code eventType} is not on the allowlist, and nothing was stored.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Acknowledgement of a domain event postback")
public record DomainEventResponse(
        @Schema(example = "ACCEPTED") String status,
        @Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") String eventId,
        @Schema(example = "EXPENSE_CREATED") String eventType,
        @Schema(description = "Server-resolved destination table", example = "continuum_home_expenses")
        String table,
        @Schema(example = "1724584284000") long timestamp,
        @Schema(description = "Present only on REJECTED", example = "unknown_event_type") String error
) {
    public static DomainEventResponse rejected(String eventType, String error) {
        return new DomainEventResponse("REJECTED", null, eventType, null, 0L, error);
    }
}
