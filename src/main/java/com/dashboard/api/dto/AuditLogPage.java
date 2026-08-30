package com.dashboard.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** A page of audit-log entries, newest first. {@code nextBefore} feeds the next {@code ?before}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditLogPage(String scope, int count, List<AuditLogEntry> results, String nextBefore) {
}
