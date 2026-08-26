package com.dashboard.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Inbound audit event.
 *
 * <p>{@code sourceApp} and {@code eventType} become Firestore query dimensions, so both are
 * length- and charset-bounded: unbounded cardinality on an indexed field is a storage cost
 * that compounds with every write.
 */
@Schema(description = "Audit event postback payload sent by personal applications to monolith-api")
public class AuditPostbackDto {

    /** Identifier-safe: letters, digits, dash, underscore, dot. Keeps indexed values predictable. */
    private static final String IDENTIFIER_PATTERN = "^[A-Za-z0-9._-]+$";

    @Schema(description = "Source application identifier", example = "continuum-home")
    @NotBlank(message = "sourceApp is required")
    @Size(max = 64, message = "sourceApp must be at most 64 characters")
    @Pattern(regexp = IDENTIFIER_PATTERN, message = "sourceApp may contain only letters, digits, '.', '-' and '_'")
    private String sourceApp;

    @Schema(description = "Event category/name", example = "USER_SESSION_ACTIVE")
    @NotBlank(message = "eventType is required")
    @Size(max = 96, message = "eventType must be at most 96 characters")
    @Pattern(regexp = IDENTIFIER_PATTERN, message = "eventType may contain only letters, digits, '.', '-' and '_'")
    private String eventType;

    @Schema(description = "Caller-generated unique ID for this logical event. Used as the "
            + "BigQuery insertId so retries/keepalive resends dedupe instead of double-counting. "
            + "Optional: falls back to the server-generated logId if absent.",
            example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    @Size(max = 128, message = "eventId must be at most 128 characters")
    private String eventId;

    @Schema(description = "Severity level; unrecognized values degrade to INFO", example = "INFO",
            allowableValues = {"DEBUG", "INFO", "WARN", "ERROR", "CRITICAL"})
    @Size(max = 16)
    private String severity = "INFO";

    @Schema(description = "User or subject ID performing the action", example = "usr_99812")
    @Size(max = 128, message = "userId must be at most 128 characters")
    private String userId;

    @Schema(description = "Unix timestamp in milliseconds; ignored if it drifts more than 24h from server time",
            example = "1724584284000")
    private Long timestamp;

    @Schema(description = "Event payload metadata; truncated per audit.max-map-entries and audit.max-value-length")
    private Map<String, Object> metadata;

    @Schema(description = "Client-reported context. Untrusted: server-observed headers take precedence for security decisions.")
    private Map<String, Object> context;

    public AuditPostbackDto() {
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

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    public void setContext(Map<String, Object> context) {
        this.context = context;
    }
}
