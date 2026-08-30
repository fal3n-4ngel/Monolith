package com.dashboard.api.controller;

import com.dashboard.api.dto.ReportListResponse;
import com.dashboard.api.reports.BigQueryReportRunner;
import com.dashboard.api.reports.ReportService;
import com.dashboard.api.security.AuthenticatedClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "Reports", description = "Admin-authored BigQuery reports, scoped per credential, returned as CSV")
@RestController
@RequestMapping({"/api/v1/reports", "/reports"})
public class ReportController {

    private static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final ReportService service;

    public ReportController(ReportService service) {
        this.service = service;
    }

    @Operation(summary = "List the reports this credential is allotted",
            security = @SecurityRequirement(name = "BearerAuth"))
    @GetMapping
    public ReportListResponse list(Authentication authentication) {
        return new ReportListResponse(service.available(AuthenticatedClient.require(authentication)));
    }

    @Operation(summary = "Run a report and return its result as CSV",
            security = @SecurityRequirement(name = "BearerAuth"))
    @PostMapping(value = "/{id}/run", produces = "text/csv")
    public ResponseEntity<String> run(@PathVariable String id,
                                      @RequestBody(required = false) Map<String, Object> body,
                                      Authentication authentication) {
        Map<String, String> params = new LinkedHashMap<>();
        if (body != null) {
            body.forEach((k, v) -> {
                if (v != null) {
                    params.put(k, String.valueOf(v));
                }
            });
        }

        BigQueryReportRunner.Result result = service.run(AuthenticatedClient.require(authentication), id, params);
        String filename = "report-" + id + "-" + LocalDateTime.now().withNano(0).toString().replace(":", "-") + ".csv";

        return ResponseEntity.ok()
                .contentType(TEXT_CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header("X-Report-Truncated", Boolean.toString(result.truncated()))
                .body(toCsv(result.columns(), result.rows()));
    }

    private static String toCsv(List<String> columns, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        appendRow(sb, columns);
        for (List<String> row : rows) {
            appendRow(sb, row);
        }
        return sb.toString();
    }

    private static void appendRow(StringBuilder sb, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(cells.get(i).replace("\"", "\"\"")).append('"');
        }
        sb.append("\r\n");
    }
}
