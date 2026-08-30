package com.dashboard.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * A page of audit-log entries, newest first.
 *
 * <p>{@code nextBefore} is keyset pagination: when it is present, pass it back as {@code ?before}
 * to fetch the next page. It is absent once a page comes back shorter than the requested limit.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A page of audit-log entries")
public record AuditLogPage(
        @Schema(description = "The app this result is confined to, or 'all' for a cross-app credential",
                example = "continuum-home")
        String scope,
        @Schema(description = "Number of entries in this page", example = "2")
        int count,
        List<AuditLogEntry> results,
        @Schema(description = "Pass as ?before for the next page; absent when there are no more rows",
                example = "2026-08-30T11:59:00Z")
        String nextBefore
) {
}
