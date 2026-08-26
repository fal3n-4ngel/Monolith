package com.dashboard.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Inbound domain event — "an expense was created." Deliberately no {@code table} field: the
 * destination is resolved server-side from {@code eventType} (see {@code DomainEventType}).
 */
@Schema(description = "Domain event emitted by a personal application when a user record changes")
public class DomainEventDto {

    /** Identifier-safe: letters, digits, dash, underscore, dot. Keeps indexed values predictable. */
    private static final String IDENTIFIER_PATTERN = "^[A-Za-z0-9._-]+$";

    @Schema(description = "Source application identifier", example = "continuum-home")
    @NotBlank(message = "sourceApp is required")
    @Size(max = 64, message = "sourceApp must be at most 64 characters")
    @Pattern(regexp = IDENTIFIER_PATTERN, message = "sourceApp may contain only letters, digits, '.', '-' and '_'")
    private String sourceApp;

    @Schema(description = "Event name; must be one of the allowlisted DomainEventType values",
            example = "EXPENSE_CREATED")
    @NotBlank(message = "eventType is required")
    @Size(max = 96, message = "eventType must be at most 96 characters")
    @Pattern(regexp = IDENTIFIER_PATTERN, message = "eventType may contain only letters, digits, '.', '-' and '_'")
    private String eventType;

    @Schema(description = "Caller-generated unique ID; used as the BigQuery insertId so retries dedupe",
            example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    @Size(max = 128, message = "eventId must be at most 128 characters")
    private String eventId;

    @Schema(description = "The acting user's ID as known to the source app. Joined to identity_links "
            + "on (source_app, local_user_id) to resolve the same person across applications.",
            example = "firebase-uid-123")
    @NotBlank(message = "userId is required")
    @Size(max = 128, message = "userId must be at most 128 characters")
    private String userId;

    @Schema(description = "ID of the record the event concerns, in the source app's own namespace",
            example = "exp_88213")
    @Size(max = 128, message = "entityId must be at most 128 characters")
    private String entityId;

    @Schema(description = "Rows affected. Greater than 1 for batch operations (CSV import, bulk sync), "
            + "which emit one event rather than one per row.", example = "1")
    @Min(value = 1, message = "itemCount must be at least 1")
    private Integer itemCount;

    @Schema(description = "Unix timestamp in milliseconds; ignored if it drifts more than 24h from server time",
            example = "1724584284000")
    private Long timestamp;

    @Schema(description = "Domain-specific detail; truncated per audit.max-map-entries and audit.max-value-length")
    private Map<String, Object> payload;

    public DomainEventDto() {
    }

    public String getSourceApp() {
        return sourceApp;
    }

    public void setSourceApp(String sourceApp) {
        this.sourceApp = sourceApp;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public Integer getItemCount() {
        return itemCount;
    }

    public void setItemCount(Integer itemCount) {
        this.itemCount = itemCount;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }
}
