package com.dashboard.api;

import com.dashboard.api.controller.HealthController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HealthControllerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new HealthController("monolith-api"))
            .build();

    @Test
    void healthEndpointReportsServiceIdentity() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("monolith-api"))
                .andExpect(jsonPath("$.timestamp").isNumber());
    }

    @Test
    void rootEndpointIsTheSameProbe() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
