package com.dashboard.api;

import com.dashboard.api.config.ReportProperties;
import com.dashboard.api.events.AppRegistry;
import com.dashboard.api.reports.ReportRegistry;
import com.dashboard.api.security.ClientRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-checks the three bundled registries against each other — the part no single
 * {@code @ConfigurationProperties} validator can see. Runs on every PR; a typo'd report id in
 * {@code apps.json} or {@code clients.json} would otherwise fail silently as "not allotted".
 */
class RegistryConsistencyTest {

    private static final DefaultResourceLoader LOADER = new DefaultResourceLoader();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final AppRegistry APPS = new AppRegistry(LOADER, MAPPER, "classpath:apps.json");
    private static final ReportRegistry REPORTS = new ReportRegistry(LOADER, MAPPER,
            new ReportProperties("classpath:reports.json", 0L, 0L, 100), APPS);
    private static final ClientRegistry CLIENTS = new ClientRegistry(LOADER, MAPPER, APPS, "classpath:clients.json");

    private static final Set<String> REPORT_IDS =
            REPORTS.reports().stream().map(ReportRegistry.ReportDefinition::id).collect(Collectors.toSet());

    @Test
    void everyReportAllottedInAppsJsonExists() {
        for (AppRegistry.AppDefinition app : APPS.apps()) {
            for (String reportId : app.readbackReports()) {
                assertThat(reportId.equals("*") || REPORT_IDS.contains(reportId))
                        .withFailMessage("apps.json: app '%s' readback allots unknown report '%s'", app.id(), reportId)
                        .isTrue();
            }
        }
    }

    @Test
    void everyReportAllottedInClientsJsonExists() {
        for (ClientRegistry.ClientDefinition client : CLIENTS.clients()) {
            for (String reportId : client.reports()) {
                assertThat(reportId.equals("*") || REPORT_IDS.contains(reportId))
                        .withFailMessage("clients.json: client '%s' allots unknown report '%s'", client.name(), reportId)
                        .isTrue();
            }
        }
    }

    @Test
    void everyReportTagIsARegisteredApp() {
        for (ReportRegistry.ReportDefinition report : REPORTS.reports()) {
            for (String tag : report.tags()) {
                assertThat(APPS.resolveApp(tag))
                        .withFailMessage("reports.json: report '%s' is tagged with unknown app '%s'", report.id(), tag)
                        .isPresent();
            }
        }
    }

    @Test
    void aScopedAppIsOnlyAllottedReportsItCouldActuallyRun() {
        // A report tagged ["x"] only applies to app x. If x's own readback allots a report
        // tagged for a *different* app, x can never run it — the tag check would 400 every time.
        for (AppRegistry.AppDefinition app : APPS.apps()) {
            for (String reportId : app.readbackReports()) {
                REPORTS.byId(reportId).ifPresent(report -> assertThat(
                        report.tags().isEmpty() || report.tags().contains(app.id()))
                        .withFailMessage("apps.json: app '%s' is allotted report '%s', which is tagged %s",
                                app.id(), reportId, report.tags())
                        .isTrue());
            }
        }
    }
}
