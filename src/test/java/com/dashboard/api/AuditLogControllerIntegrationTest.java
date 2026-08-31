package com.dashboard.api;

import com.dashboard.api.dto.AuditLogEntry;
import com.dashboard.api.events.AppRef;
import com.dashboard.api.query.BigQueryAuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "API_KEY=admin_test_key_A1",
        "MONOLITH_CLIENT_KEYS=continuum-home=continuum_test_key_B2"
})
@AutoConfigureMockMvc
class AuditLogControllerIntegrationTest {

    private static final String ADMIN = "Bearer admin_test_key_A1";
    private static final String CONTINUUM = "Bearer continuum_test_key_B2";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private BigQueryAuditLogRepository repository;

    @Test
    void unauthenticatedIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/audit/logs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void scopedCredentialCannotQueryAnotherApp() throws Exception {
        mockMvc.perform(get("/api/v1/audit/logs")
                        .param("sourceApp", "monolith-dashboard")
                        .header("Authorization", CONTINUUM))
                .andExpect(status().isForbidden());

        verify(repository, never()).search(any());
    }

    @Test
    void scopedCredentialQueryIsForcedToItsOwnApp() throws Exception {
        when(repository.search(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/audit/logs").header("Authorization", CONTINUUM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("continuum-home"))
                .andExpect(jsonPath("$.count").value(0));

        ArgumentCaptor<BigQueryAuditLogRepository.Criteria> captor =
                ArgumentCaptor.forClass(BigQueryAuditLogRepository.Criteria.class);
        verify(repository).search(captor.capture());
        assertThat(captor.getValue().sourceApp()).contains(new AppRef("continuum-home"));
    }

    @Test
    void crossAppCredentialSeesEveryAppByDefault() throws Exception {
        when(repository.search(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/audit/logs").header("Authorization", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("all"));

        ArgumentCaptor<BigQueryAuditLogRepository.Criteria> captor =
                ArgumentCaptor.forClass(BigQueryAuditLogRepository.Criteria.class);
        verify(repository).search(captor.capture());
        assertThat(captor.getValue().sourceApp()).isEmpty();
    }

    @Test
    void unknownEventTypeIsRejectedWithBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/audit/logs")
                        .param("eventType", "NONSENSE")
                        .header("Authorization", ADMIN))
                .andExpect(status().isBadRequest());

        verify(repository, never()).search(any());
    }

    @Test
    void payloadIsReturnedAsJsonNotAnEscapedString() throws Exception {
        AuditLogEntry entry = new AuditLogEntry("expenses", "evt-1", "continuum-home", "usr_1",
                "EXPENSE_CREATED", "CREATE", "exp_1", 1L,
                "2026-08-30T12:00:00Z", "2026-08-30T12:00:01Z",
                objectMapper.readTree("{\"amount\":42.5,\"category\":\"food\"}"));
        when(repository.search(any())).thenReturn(List.of(entry));

        mockMvc.perform(get("/api/v1/audit/logs").header("Authorization", CONTINUUM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.results[0].eventType").value("EXPENSE_CREATED"))
                .andExpect(jsonPath("$.results[0].payload.amount").value(42.5))
                .andExpect(jsonPath("$.results[0].payload.category").value("food"));
    }
}
