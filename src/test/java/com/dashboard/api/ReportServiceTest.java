package com.dashboard.api;

import com.dashboard.api.config.ReportProperties;
import com.dashboard.api.events.AppRef;
import com.dashboard.api.events.AppRegistry;
import com.dashboard.api.reports.BigQueryReportRunner;
import com.dashboard.api.reports.ReportRegistry;
import com.dashboard.api.reports.ReportService;
import com.dashboard.api.security.AuthenticatedClient;
import com.dashboard.api.security.ClientRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.bigquery.QueryParameterValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReportServiceTest {

    private BigQueryReportRunner runner;
    private ReportService service;

    @BeforeEach
    void setUp() {
        DefaultResourceLoader loader = new DefaultResourceLoader();
        ObjectMapper mapper = new ObjectMapper();
        AppRegistry apps = new AppRegistry(loader, mapper, "classpath:apps.json");
        ReportRegistry reports = new ReportRegistry(loader, mapper,
                new ReportProperties("classpath:reports.json", 0L, 0L, 100), apps);
        ClientRegistry clients = new ClientRegistry(loader, mapper, apps, "classpath:clients.json");

        runner = mock(BigQueryReportRunner.class);
        when(runner.run(anyString(), any()))
                .thenReturn(new BigQueryReportRunner.Result(List.of("x"), List.of(List.of("1")), false));

        service = new ReportService(reports, clients, runner, apps);
    }

    private static final AuthenticatedClient OWNER = AuthenticatedClient.crossApp("owner");
    private static final AuthenticatedClient CONTINUUM =
            AuthenticatedClient.boundTo("continuum-home", new AppRef("continuum-home"));

    @SuppressWarnings("unchecked")
    private Map<String, QueryParameterValue> capture() {
        ArgumentCaptor<Map<String, QueryParameterValue>> captor = ArgumentCaptor.forClass(Map.class);
        verify(runner).run(anyString(), captor.capture());
        return captor.getValue();
    }

    @Test
    void crossAppCredentialSeesEveryReport() {
        assertThat(service.available(OWNER)).extracting(r -> r.id())
                .contains("audit-log", "all-apps-volume", "ingest-latency");
    }

    @Test
    void scopedCredentialSeesOnlyItsAllottedReports() {
        assertThat(service.available(CONTINUUM)).extracting(r -> r.id())
                .contains("audit-log", "daily-volume")
                .doesNotContain("all-apps-volume");
    }

    @Test
    void selectableAppsIsEveryAppForTheOwnerAndJustItsOwnForAScopedClient() {
        assertThat(service.selectableApps(OWNER)).contains("continuum-home", "monolith-dashboard");
        assertThat(service.selectableApps(CONTINUUM)).containsExactly("continuum-home");
    }

    @Test
    void runningAnUnallottedReportIsForbidden() {
        assertThatThrownBy(() -> service.run(CONTINUUM, "all-apps-volume", Map.of("from", "2026-01-01T00:00:00Z")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not allotted");
        verifyNoInteractions(runner);
    }

    @Test
    void runningAnUnknownReportIs404() {
        assertThatThrownBy(() -> service.run(OWNER, "ghost", Map.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("unknown report");
    }

    @Test
    void aMissingRequiredParameterIsRejected() {
        assertThatThrownBy(() -> service.run(CONTINUUM, "audit-log", Map.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("missing required parameter: from");
        verifyNoInteractions(runner);
    }

    @Test
    void scopedRunPinsCallerAppToTheCredentialsOwnApp() {
        service.run(CONTINUUM, "audit-log", Map.of("from", "2026-01-01T00:00:00Z"));

        Map<String, QueryParameterValue> bound = capture();
        assertThat(bound).containsKey("from");
        assertThat(bound.get("caller_app").getValue()).isEqualTo("continuum-home");
    }

    @Test
    void crossAppRunNeedsAnExplicitCallerAppForAPerClientReport() {
        assertThatThrownBy(() -> service.run(OWNER, "audit-log", Map.of("from", "2026-01-01T00:00:00Z")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("callerApp is required");
    }

    @Test
    void crossAppRunAcceptsACallerApp() {
        service.run(OWNER, "audit-log",
                Map.of("from", "2026-01-01T00:00:00Z", "callerApp", "continuum-home"));

        assertThat(capture().get("caller_app").getValue()).isEqualTo("continuum-home");
    }

    @Test
    void aReportWithoutCallerAppBindsNoCallerApp() {
        service.run(OWNER, "all-apps-volume", Map.of("from", "2026-01-01T00:00:00Z"));

        assertThat(capture()).doesNotContainKey("caller_app");
    }

    @Test
    void aTaggedReportRejectsAnAppItIsNotTiedTo() {
        assertThatThrownBy(() -> service.run(OWNER, "expense-spend",
                Map.of("from", "2026-01-01T00:00:00Z", "callerApp", "monolith-dashboard")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("only applies to: continuum-home");
        verifyNoInteractions(runner);
    }

    @Test
    void aTaggedReportRunsForTheAppItIsTiedTo() {
        service.run(CONTINUUM, "expense-spend", Map.of("from", "2026-01-01T00:00:00Z"));

        assertThat(capture().get("caller_app").getValue()).isEqualTo("continuum-home");
    }
}
