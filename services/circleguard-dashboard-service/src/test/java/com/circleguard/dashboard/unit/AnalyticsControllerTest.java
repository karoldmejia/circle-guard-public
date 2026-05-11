package com.circleguard.dashboard.controller;

import com.circleguard.dashboard.service.AnalyticsService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AnalyticsController.class)
class AnalyticsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AnalyticsService analyticsService;

    @Test
    void shouldReturnHealthBoardStats() throws Exception {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalGreen", 1500);
        stats.put("totalExposed", 45);

        Mockito.when(analyticsService.getGlobalHealthStats()).thenReturn(stats);

        mockMvc.perform(get("/api/v1/analytics/health-board")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalGreen").value(1500))
                .andExpect(jsonPath("$.totalExposed").value(45));
    }

    @Test
    void shouldReturnTrendsForLocation() throws Exception {
        UUID locationId = UUID.randomUUID();
        List<Map<String, Object>> trends = new ArrayList<>();
        Map<String, Object> entry = new HashMap<>();
        entry.put("hour", "08:00");
        entry.put("count", 120);
        trends.add(entry);

        Mockito.when(analyticsService.getEntryTrends(locationId)).thenReturn(trends);

        mockMvc.perform(get("/api/v1/analytics/trends/" + locationId)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].hour").value("08:00"))
                .andExpect(jsonPath("$[0].count").value(120));
    }
}
