package com.dashboard.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"MONOLITH_CLIENT_KEYS=continuum=expenses_adi_secret_9k2mXp7vLqR4"})
@AutoConfigureMockMvc
class DomainEventControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void postbackRejectsUnauthenticatedCallsWhenKeyConfigured() throws Exception {
        Map<String, Object> payload = Map.of(
                "eventType", "EXPENSE_CREATED",
                "sourceApp", "continuum-home",
                "payload", Map.of("amount", 10.50)
        );

        mockMvc.perform(post("/api/v1/events/postback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void postbackAcceptsValidEventWithBearerAuth() throws Exception {
        Map<String, Object> payload = Map.of(
                "eventType", "EXPENSE_CREATED",
                "sourceApp", "continuum-home",
                "userId", "user_123",
                "entityId", "exp_999",
                "payload", Map.of("amount", 25.00, "category", "Food")
        );

        mockMvc.perform(post("/api/v1/events/postback")
                        .header("Authorization", "Bearer expenses_adi_secret_9k2mXp7vLqR4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.eventType").value("EXPENSE_CREATED"))
                .andExpect(jsonPath("$.table").value("continuum_home_expenses"))
                .andExpect(jsonPath("$.eventId").exists());
    }

    @Test
    void postbackRejectsUnknownEventType() throws Exception {
        Map<String, Object> payload = Map.of(
                "eventType", "INVALID_UNALLOWED_TYPE",
                "sourceApp", "continuum-home",
                "userId", "user_123",
                "payload", Map.of("foo", "bar")
        );

        mockMvc.perform(post("/api/v1/events/postback")
                        .header("Authorization", "Bearer expenses_adi_secret_9k2mXp7vLqR4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.error").value("unknown_event_type"));
    }

    @Test
    void postbackRejectsMissingRequiredFields() throws Exception {
        Map<String, Object> payload = Map.of(
                "payload", Map.of("amount", 10.50)
        );

        mockMvc.perform(post("/api/v1/events/postback")
                        .header("Authorization", "Bearer expenses_adi_secret_9k2mXp7vLqR4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest());
    }
}
