package com.dashboard.api.reports;

import com.dashboard.api.dto.ReportSummary;
import com.dashboard.api.events.SourceApp;
import com.dashboard.api.reports.ReportRegistry.ParamSpec;
import com.dashboard.api.reports.ReportRegistry.ReportDefinition;
import com.dashboard.api.security.AuthenticatedClient;
import com.dashboard.api.security.ClientRegistry;
import com.google.cloud.bigquery.QueryParameterValue;
import com.google.cloud.bigquery.StandardSQLTypeName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a credential + report id + raw param strings into a bound, scoped BigQuery run. A
 * cross-app credential may run any report; a scoped one only the ids listed for it in
 * {@code clients.json}. {@code @caller_app}, where a report uses it, is the scoped credential's
 * own app.
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final ReportRegistry registry;
    private final ClientRegistry clients;
    private final BigQueryReportRunner runner;

    public ReportService(ReportRegistry registry, ClientRegistry clients, BigQueryReportRunner runner) {
        this.registry = registry;
        this.clients = clients;
        this.runner = runner;
    }

    public List<ReportSummary> available(AuthenticatedClient client) {
        return registry.reports().stream()
                .filter(report -> mayRun(client, report.id()))
                .map(ReportService::toSummary)
                .toList();
    }

    /** Clients this credential may run a report for: all of them for a cross-app credential, else its own. */
    public List<String> selectableApps(AuthenticatedClient client) {
        if (client.isCrossApp()) {
            return Arrays.stream(SourceApp.values()).map(SourceApp::appId).toList();
        }
        return client.boundApp().map(app -> List.of(app.appId())).orElse(List.of());
    }

    public BigQueryReportRunner.Result run(AuthenticatedClient client, String reportId, Map<String, String> rawParams) {
        ReportDefinition report = registry.byId(reportId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown report: " + reportId));
        if (!mayRun(client, reportId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "this credential is not allotted the report '" + reportId + "'");
        }

        Map<String, QueryParameterValue> bound = new LinkedHashMap<>();
        for (ParamSpec param : report.params()) {
            String raw = rawParams == null ? null : rawParams.get(param.name());
            if (raw == null || raw.isBlank()) {
                if (param.required()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "missing required parameter: " + param.name());
                }
                bound.put(param.name(), QueryParameterValue.newBuilder().setType(sqlType(param.type())).build());
            } else {
                bound.put(param.name(), parse(param.name(), param.type(), raw.trim()));
            }
        }
        if (report.referencesCallerApp()) {
            String callerApp = resolveCallerApp(client, rawParams);
            if (!report.tags().isEmpty() && !report.tags().contains(callerApp)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "report '" + reportId + "' only applies to: " + String.join(", ", report.tags()));
            }
            bound.put("caller_app", QueryParameterValue.string(callerApp));
        }

        log.info("[Reports] run id={} client={} params={}", reportId, client.name(), bound.keySet());
        return runner.run(report.sql(), bound);
    }

    private boolean mayRun(AuthenticatedClient client, String reportId) {
        if (client.isCrossApp()) {
            return true;
        }
        List<String> allotted = clients.reportsFor(client.name());
        return allotted.contains("*") || allotted.contains(reportId);
    }

    private String resolveCallerApp(AuthenticatedClient client, Map<String, String> rawParams) {
        String requested = rawParams == null ? null : rawParams.get("callerApp");
        if (client.isCrossApp()) {
            if (requested == null || requested.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "callerApp is required for a cross-app credential on this report");
            }
            return SourceApp.parse(requested)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown callerApp: " + requested))
                    .appId();
        }
        SourceApp bound = client.boundApp()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "this credential has no app scope"));
        if (requested != null && !requested.isBlank() && !requested.trim().equalsIgnoreCase(bound.appId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "this credential is scoped to '" + bound.appId() + "'");
        }
        return bound.appId();
    }

    private static QueryParameterValue parse(String name, String type, String raw) {
        try {
            return switch (type) {
                case "timestamp" -> QueryParameterValue.timestamp(toMicros(raw));
                case "date" -> QueryParameterValue.date(raw);
                case "int64" -> QueryParameterValue.int64(Long.parseLong(raw));
                case "bool" -> QueryParameterValue.bool(toBool(raw));
                default -> QueryParameterValue.string(raw);
            };
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "parameter '" + name + "' is not a valid " + type);
        }
    }

    private static StandardSQLTypeName sqlType(String type) {
        return switch (type) {
            case "timestamp" -> StandardSQLTypeName.TIMESTAMP;
            case "date" -> StandardSQLTypeName.DATE;
            case "int64" -> StandardSQLTypeName.INT64;
            case "bool" -> StandardSQLTypeName.BOOL;
            default -> StandardSQLTypeName.STRING;
        };
    }

    private static long toMicros(String raw) {
        Instant instant = !raw.isEmpty() && raw.chars().allMatch(Character::isDigit)
                ? Instant.ofEpochMilli(Long.parseLong(raw))
                : Instant.parse(raw);
        return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000L;
    }

    private static boolean toBool(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes" -> true;
            case "false", "0", "no" -> false;
            default -> throw new IllegalArgumentException("not a bool");
        };
    }

    private static ReportSummary toSummary(ReportDefinition report) {
        return new ReportSummary(report.id(), report.name(), report.description(),
                report.referencesCallerApp(), report.tags(),
                report.params().stream()
                        .map(p -> new ReportSummary.Param(p.name(), p.type(), p.required()))
                        .toList());
    }
}
