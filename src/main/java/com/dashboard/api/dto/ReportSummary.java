package com.dashboard.api.dto;

import java.util.List;

public record ReportSummary(
        String id,
        String name,
        String description,
        boolean needsCallerApp,
        List<Param> params
) {
    public record Param(String name, String type, boolean required) {
    }
}
