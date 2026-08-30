package com.dashboard.api;

import com.dashboard.api.config.RateLimitFilter;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RateLimitFilterTest {

    @RestController
    static class TestController {
        @GetMapping("/health")
        String health() {
            return "ok";
        }

        @PostMapping("/api/v1/events/postback")
        String postback() {
            return "ok";
        }

        @GetMapping("/api/v1/audit/logs")
        String auditLogs() {
            return "ok";
        }
    }

    private MockMvc mockMvcWith(int globalLimit, int postbackLimit) {
        return mockMvcWith(globalLimit, postbackLimit, 0);
    }

    private MockMvc mockMvcWith(int globalLimit, int postbackLimit, int readLimit) {
        return MockMvcBuilders.standaloneSetup(new TestController())
                .addFilter(new RateLimitFilter(globalLimit, postbackLimit, readLimit))
                .build();
    }

    @Test
    void allowsRequestsWithinGlobalBudget() throws Exception {
        MockMvc mockMvc = mockMvcWith(2, 0);

        mockMvc.perform(get("/health")).andExpect(status().isOk());
        mockMvc.perform(get("/health")).andExpect(status().isOk());
    }

    @Test
    void rejectsOnceGlobalBudgetIsExceeded() throws Exception {
        MockMvc mockMvc = mockMvcWith(2, 0);

        mockMvc.perform(get("/health")).andExpect(status().isOk());
        mockMvc.perform(get("/health")).andExpect(status().isOk());
        mockMvc.perform(get("/health"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));
    }

    @Test
    void postbackHasItsOwnTighterBudgetThanTheGlobalOne() throws Exception {
        MockMvc mockMvc = mockMvcWith(100, 1);

        mockMvc.perform(post("/api/v1/events/postback").header("Authorization", "Bearer key-a"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/events/postback").header("Authorization", "Bearer key-a"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void perCredentialBudgetTriggersEvenFromDifferentIpsWithSameKey() throws Exception {
        MockMvc mockMvc = mockMvcWith(100, 1);

        mockMvc.perform(post("/api/v1/events/postback")
                        .header("Authorization", "Bearer shared-key")
                        .header("X-Forwarded-For", "1.1.1.1"))
                .andExpect(status().isOk());

        // Different source IP, same leaked credential — the per-IP budget alone would miss this.
        mockMvc.perform(post("/api/v1/events/postback")
                        .header("Authorization", "Bearer shared-key")
                        .header("X-Forwarded-For", "2.2.2.2"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void differentCredentialsFromTheSameIpGetIndependentBudgets() throws Exception {
        MockMvc mockMvc = mockMvcWith(100, 1);

        mockMvc.perform(post("/api/v1/events/postback")
                        .header("Authorization", "Bearer key-one")
                        .header("X-Forwarded-For", "9.9.9.9"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/events/postback")
                        .header("Authorization", "Bearer key-two")
                        .header("X-Forwarded-For", "9.9.9.9"))
                .andExpect(status().isTooManyRequests()); // per-IP budget (1/min) is shared and now spent
    }

    @Test
    void zeroDisablesRateLimitingEntirely() throws Exception {
        MockMvc mockMvc = mockMvcWith(0, 0, 0);

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/health")).andExpect(status().isOk());
        }
    }

    @Test
    void auditQueryHasItsOwnPerCredentialBudgetAcrossIps() throws Exception {
        MockMvc mockMvc = mockMvcWith(100, 0, 1);

        mockMvc.perform(get("/api/v1/audit/logs")
                        .header("Authorization", "Bearer query-key")
                        .header("X-Forwarded-For", "5.5.5.5"))
                .andExpect(status().isOk());

        // Same credential, different IP — the per-credential ceiling still catches it.
        mockMvc.perform(get("/api/v1/audit/logs")
                        .header("Authorization", "Bearer query-key")
                        .header("X-Forwarded-For", "6.6.6.6"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));
    }

    @Test
    void auditQueryIsUntouchedWhenOnlyTheReadLimitIsZero() throws Exception {
        MockMvc mockMvc = mockMvcWith(100, 100, 0);

        for (int i = 0; i < 4; i++) {
            mockMvc.perform(get("/api/v1/audit/logs").header("Authorization", "Bearer k"))
                    .andExpect(status().isOk());
        }
    }
}
