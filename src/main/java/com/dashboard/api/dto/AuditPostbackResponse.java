package com.dashboard.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Acknowledgement returned to the caller.
 *
 * <p>{@code ACCEPTED} means the event was validated and queued — persistence happens
 * off the request thread, so callers must not treat this as a durability guarantee.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Acknowledgement of an accepted audit postback")
public record AuditPostbackResponse(
        @Schema(example = "ACCEPTED") String status,
        @Schema(example = "0f8c1b2e-1f4a-4a9f-9a6e-2c3d4e5f6a7b") String logId,
        @Schema(example = "continuum-home") String sourceApp,
        @Schema(example = "USER_SESSION_ACTIVE") String eventType,
        @Schema(example = "1724584284000") long timestamp,
        @Schema(description = "Present only when the event tripped a security rule",
                example = "UNAUTHORIZED_DEPLOYMENT_ORIGIN") String securityAlert
) {
    public static final String ALERT_UNAUTHORIZED_ORIGIN = "UNAUTHORIZED_DEPLOYMENT_ORIGIN";
}
