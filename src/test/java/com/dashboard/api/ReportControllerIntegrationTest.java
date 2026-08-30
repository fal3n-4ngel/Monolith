package com.dashboard.api;

import com.dashboard.api.reports.BigQueryReportRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "API_KEY=admin_test_key_A1",
        "MONOLITH_CLIENT_KEYS=continuum=continuum_test_key_B2"
})
@AutoConfigureMockMvc
class ReportControllerIntegrationTest {

    private static final String ADMIN = "Bearer admin_test_key_A1";
    private static final String CONTINUUM = "Bearer continuum_test_key_B2";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BigQueryReportRunner runner;

    @Test
    void listingRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/v1/reports")).andExpect(status().isUnauthorized());
    }

    @Test
    void aCrossAppCredentialSeesEveryReportAndEveryApp() throws Exception {
        mockMvc.perform(get("/api/v1/reports").header("Authorization", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports.length()").value(17))
                .andExpect(jsonPath("$.apps").value(org.hamcrest.Matchers.hasItem("continuum-home")))
                .andExpect(jsonPath("$.apps").value(org.hamcrest.Matchers.hasItem("monolith-dashboard")));
    }

    @Test
    void aScopedCredentialSeesOnlyItsAllottedReportsAndItsOwnApp() throws Exception {
        mockMvc.perform(get("/api/v1/reports").header("Authorization", CONTINUUM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports.length()").value(11))
                .andExpect(jsonPath("$.apps").value(org.hamcrest.Matchers.contains("continuum-home")));
    }

    @Test
    void runningAnAllottedReportReturnsCsv() throws Exception {
        when(runner.run(anyString(), any())).thenReturn(
                new BigQueryReportRunner.Result(List.of("day", "events"), List.of(List.of("2026-01-01", "5")), false));

        mockMvc.perform(post("/api/v1/reports/audit-log/run")
                        .header("Authorization", CONTINUUM)
                        .contentType("application/json")
                        .content("{\"from\":\"2026-01-01T00:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("\"day\",\"events\"")));
    }

    @Test
    void runningAnUnallottedReportIsForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/reports/all-apps-volume/run")
                        .header("Authorization", CONTINUUM)
                        .contentType("application/json")
                        .content("{\"from\":\"2026-01-01T00:00:00Z\"}"))
                .andExpect(status().isForbidden());

        verify(runner, never()).run(anyString(), any());
    }

    @Test
    void runningAnUnknownReportIs404() throws Exception {
        mockMvc.perform(post("/api/v1/reports/ghost/run")
                        .header("Authorization", ADMIN)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aCrossAppRunOfAPerClientReportNeedsCallerApp() throws Exception {
        mockMvc.perform(post("/api/v1/reports/audit-log/run")
                        .header("Authorization", ADMIN)
                        .contentType("application/json")
                        .content("{\"from\":\"2026-01-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest());
    }
}
