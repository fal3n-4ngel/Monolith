package com.dashboard.api.dto;

import java.util.List;

/** {@code apps} is the clients the caller may run a report for (its own, or all for the owner). */
public record ReportListResponse(List<ReportSummary> reports, List<String> apps) {
}
