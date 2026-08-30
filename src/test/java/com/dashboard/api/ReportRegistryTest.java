package com.dashboard.api;

import com.dashboard.api.config.ReportProperties;
import com.dashboard.api.reports.ReportRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportRegistryTest {

    private ReportRegistry load(String location) {
        ReportProperties props = new ReportProperties(location, 200_000_000L, 30_000L, 50_000);
        return new ReportRegistry(new DefaultResourceLoader(), new ObjectMapper(), props);
    }

    @Test
    void loadsTheBundledCatalog() {
        ReportRegistry registry = load("classpath:reports.json");

        assertThat(registry.reports()).extracting(ReportRegistry.ReportDefinition::id)
                .contains("audit-log", "event-type-summary", "daily-volume", "all-apps-volume");
        assertThat(registry.byId("audit-log")).isPresent();
        assertThat(registry.byId("nope")).isEmpty();
    }

    @Test
    void aPerClientReportIsFlaggedAsUsingCallerApp() {
        ReportRegistry registry = load("classpath:reports.json");

        assertThat(registry.byId("audit-log").orElseThrow().referencesCallerApp()).isTrue();
        assertThat(registry.byId("all-apps-volume").orElseThrow().referencesCallerApp()).isFalse();
    }

    @Test
    void aNonSelectStatementFailsAtStartup() {
        assertThatThrownBy(() -> load("classpath:reports-bad-sql.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SELECT or WITH");
    }

    @Test
    void aDuplicateIdFailsAtStartup() {
        assertThatThrownBy(() -> load("classpath:reports-duplicate.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate id");
    }

    @Test
    void aMissingCatalogFailsAtStartup() {
        assertThatThrownBy(() -> load("classpath:no-such-reports.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }
}
