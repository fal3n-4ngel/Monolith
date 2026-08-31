package com.dashboard.api.reports;

import com.dashboard.api.config.ReportProperties;
import com.dashboard.api.events.AppRegistry;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The checked-in report catalog ({@code reports.json}). Each report is one parameterised
 * SELECT/WITH; startup rejects anything that isn't a single read-only statement, an unknown
 * param type, or a duplicate id.
 */
@Component
public class ReportRegistry {

    private static final Logger log = LoggerFactory.getLogger(ReportRegistry.class);
    private static final Set<String> PARAM_TYPES = Set.of("string", "timestamp", "date", "int64", "bool");

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ParamSpec(String name, String type, boolean required) {
        public ParamSpec {
            type = (type == null || type.isBlank()) ? "string" : type.trim().toLowerCase(Locale.ROOT);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReportDefinition(String id, String name, String description, List<String> tags,
                                   List<ParamSpec> params, String sql) {
        public ReportDefinition {
            tags = tags == null ? List.of() : List.copyOf(tags);
            params = params == null ? List.of() : List.copyOf(params);
        }

        public boolean referencesCallerApp() {
            return sql != null && sql.contains("@caller_app");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Catalog(List<ReportDefinition> reports) {
    }

    private final List<ReportDefinition> reports;
    private final AppRegistry appRegistry;

    public ReportRegistry(ResourceLoader resourceLoader, ObjectMapper objectMapper, ReportProperties props,
                          AppRegistry appRegistry) {
        this.appRegistry = appRegistry;
        String location = props.file();
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("Report catalog not found at '" + location + "'");
        }
        try (InputStream in = resource.getInputStream()) {
            Catalog parsed = objectMapper.readValue(in, Catalog.class);
            this.reports = parsed.reports() == null ? List.of() : List.copyOf(parsed.reports());
        } catch (IOException e) {
            throw new IllegalStateException("Could not read report catalog at '" + location + "'", e);
        }
        validate();
        log.info("[Reports] Loaded {} report(s) from '{}'", reports.size(), location);
    }

    public List<ReportDefinition> reports() {
        return reports;
    }

    public Optional<ReportDefinition> byId(String id) {
        return reports.stream().filter(r -> r.id().equals(id)).findFirst();
    }

    private void validate() {
        Set<String> ids = new HashSet<>();
        for (ReportDefinition report : reports) {
            if (blank(report.id())) {
                throw new IllegalStateException("Report catalog has an entry with no 'id'");
            }
            if (!ids.add(report.id())) {
                throw new IllegalStateException("Report catalog has a duplicate id: '" + report.id() + "'");
            }
            if (blank(report.name())) {
                throw new IllegalStateException("Report '" + report.id() + "' has no 'name'");
            }
            requireReadOnlySelect(report);
            for (String tag : report.tags()) {
                if (appRegistry.resolveApp(tag).isEmpty()) {
                    throw new IllegalStateException("Report '" + report.id() + "' tag '" + tag
                            + "' is not a registered app id");
                }
            }
            for (ParamSpec param : report.params()) {
                if (blank(param.name())) {
                    throw new IllegalStateException("Report '" + report.id() + "' has a param with no 'name'");
                }
                if (!PARAM_TYPES.contains(param.type())) {
                    throw new IllegalStateException("Report '" + report.id() + "' param '" + param.name()
                            + "' has unknown type '" + param.type() + "'; expected one of " + PARAM_TYPES);
                }
            }
        }
    }

    // A statement that starts with SELECT/WITH and has no ';' cannot mutate or chain — enough
    // to guarantee read-only without a keyword blacklist.
    private static void requireReadOnlySelect(ReportDefinition report) {
        String sql = report.sql() == null ? "" : report.sql().trim();
        if (sql.isEmpty()) {
            throw new IllegalStateException("Report '" + report.id() + "' has no 'sql'");
        }
        String body = sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql;
        if (body.contains(";")) {
            throw new IllegalStateException("Report '" + report.id() + "' sql must be a single statement (no ';')");
        }
        String head = stripLeadingComments(body).toUpperCase(Locale.ROOT);
        if (!head.startsWith("SELECT") && !head.startsWith("WITH")) {
            throw new IllegalStateException("Report '" + report.id() + "' sql must start with SELECT or WITH");
        }
    }

    private static String stripLeadingComments(String sql) {
        String s = sql;
        while (true) {
            s = s.stripLeading();
            if (s.startsWith("--")) {
                int nl = s.indexOf('\n');
                s = nl < 0 ? "" : s.substring(nl + 1);
            } else if (s.startsWith("/*")) {
                int end = s.indexOf("*/");
                s = end < 0 ? "" : s.substring(end + 2);
            } else {
                return s;
            }
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
