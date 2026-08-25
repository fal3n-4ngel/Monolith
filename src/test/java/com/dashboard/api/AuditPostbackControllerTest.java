package com.dashboard.api;

import com.dashboard.api.controller.AuditPostbackController;
import com.dashboard.api.dto.AuditPostbackDto;
import com.dashboard.api.dto.AuditPostbackResponse;
import com.dashboard.api.service.AuditPostbackService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuditPostbackControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private AuditPostbackService service;

    @BeforeEach
    void setUp() {
        service = mock(AuditPostbackService.class);
        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders.standaloneSetup(new AuditPostbackController(service))
                .setValidator(new LocalValidatorFactoryBean())
                .build();
    }

    private String json(String sourceApp, String eventType) throws Exception {
        AuditPostbackDto dto = new AuditPostbackDto();
        dto.setSourceApp(sourceApp);
        dto.setEventType(eventType);
        return objectMapper.writeValueAsString(dto);
    }

    @Test
    void acceptsPostbackAndReturns202() throws Exception {
        when(service.record(any(), any())).thenReturn(new AuditPostbackResponse(
                "ACCEPTED", "log-123", "continuum-home", "USER_SESSION_ACTIVE", 1724584284000L, null));

        mockMvc.perform(post("/api/v1/audit/postback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("continuum-home", "USER_SESSION_ACTIVE")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.logId").value("log-123"))
                .andExpect(jsonPath("$.securityAlert").doesNotExist());
    }

    @Test
    void servesLegacyAliasPaths() throws Exception {
        when(service.record(any(), any())).thenReturn(new AuditPostbackResponse(
                "ACCEPTED", "log-456", "continuum-home", "ASSET_SOLD", 1L, null));

        mockMvc.perform(post("/audit/postback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("continuum-home", "ASSET_SOLD")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.logId").value("log-456"));
    }

    @Test
    void forwardsServerObservedHeadersRatherThanBodyClaims() throws Exception {
        when(service.record(any(), any())).thenReturn(new AuditPostbackResponse(
                "ACCEPTED", "log-789", "continuum-home", "USER_LOGIN", 1L, null));

        mockMvc.perform(post("/api/v1/audit/postback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Origin", "https://continuum-home.vercel.app")
                        .header("User-Agent", "Mozilla/5.0")
                        .header("X-Forwarded-For", "203.0.113.9, 70.41.3.18")
                        .content(json("continuum-home", "USER_LOGIN")))
                .andExpect(status().isAccepted());

        ArgumentCaptor<AuditPostbackService.RequestContext> captor =
                ArgumentCaptor.forClass(AuditPostbackService.RequestContext.class);
        verify(service).record(any(), captor.capture());

        AuditPostbackService.RequestContext context = captor.getValue();
        assertThat(context.origin()).isEqualTo("https://continuum-home.vercel.app");
        assertThat(context.userAgent()).isEqualTo("Mozilla/5.0");
        // First hop of X-Forwarded-For is the closest thing to the true client.
        assertThat(context.clientIp()).isEqualTo("203.0.113.9");
    }

    @Test
    void rejectsMissingSourceAppWithoutTouchingTheService() throws Exception {
        mockMvc.perform(post("/api/v1/audit/postback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(null, "USER_LOGIN")))
                .andExpect(status().isBadRequest());

        verify(service, never()).record(any(), any());
    }

    @Test
    void rejectsUnboundedIdentifierValues() throws Exception {
        mockMvc.perform(post("/api/v1/audit/postback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("continuum-home", "a".repeat(200))))
                .andExpect(status().isBadRequest());

        verify(service, never()).record(any(), any());
    }

    @Test
    void queriesLogsWithSuppliedFilters() throws Exception {
        when(service.queryAuditLogs(eq("continuum-home"), any(), any(), any()))
                .thenReturn(List.of(Map.of("logId", "log-1", "sourceApp", "continuum-home")));

        mockMvc.perform(get("/api/v1/audit/logs").param("sourceApp", "continuum-home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].logId").value("log-1"));
    }
}
