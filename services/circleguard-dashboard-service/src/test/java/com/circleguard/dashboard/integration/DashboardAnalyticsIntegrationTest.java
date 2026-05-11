package com.circleguard.dashboard.integration;

import com.circleguard.dashboard.DashboardApplication;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for Dashboard service.
 * Validates analytics queries, k-anonymity filter, and promotion client mock.
 *
 * PRIVACY REQUIREMENT: No individual anonymousId in any response.
 * K-ANONYMITY: groups with < K members must not be shown.
 */
@SpringBootTest(classes = DashboardApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DashboardAnalyticsIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("circleguard_dashboard")
        .withUsername("admin")
        .withPassword("password");

    private static WireMockServer wireMock;

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("promotion.service.url", () -> "http://localhost:8099");
    }

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(8099);
        wireMock.start();
        WireMock.configureFor("localhost", 8099);

        // Stub promotion stats endpoint
        stubFor(get(urlPathEqualTo("/api/v1/health-status/stats"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "total": 1000,
                      "confirmed": 5,
                      "suspect": 23,
                      "probable": 12,
                      "active": 960
                    }
                    """)));

        stubFor(get(urlPathMatching("/api/v1/health-status/stats/department/.*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"department":"Engineering","confirmed":2,"suspect":8,"probable":3,"total":120}
                    """)));
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedData() {
        // Seed minimal dashboard data for query tests
        jdbcTemplate.execute("""
            INSERT INTO location_events (anonymous_id, location_id, event_time, department)
            VALUES
              ('anon-aaa-111', 'loc-001', NOW() - INTERVAL '1 hour', 'Engineering'),
              ('anon-bbb-222', 'loc-001', NOW() - INTERVAL '2 hours', 'Engineering'),
              ('anon-ccc-333', 'loc-002', NOW() - INTERVAL '30 minutes', 'Sciences'),
              ('anon-ddd-444', 'loc-001', NOW() - INTERVAL '3 hours', 'Engineering'),
              ('anon-eee-555', 'loc-003', NOW() - INTERVAL '1 day', 'Arts')
            ON CONFLICT DO NOTHING
            """);
    }

    // Integration Test 1: Health board returns aggregated stats
    @Test
    @Order(1)
    @DisplayName("GET /analytics/health-board: should return aggregated counts from promotion")
    void healthBoard_returnsAggregatedStats() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/health-board")
                .header("Authorization", "Bearer test-admin-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").isNumber())
            .andExpect(jsonPath("$.confirmed").isNumber());
    }

    // Integration Test 2: Response does NOT contain individual anonymousId 
    @Test
    @Order(2)
    @DisplayName("GET /analytics/health-board: response must NOT expose individual anonymousIds")
    void healthBoard_noIndividualAnonymousIdInResponse() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/health-board")
                .header("Authorization", "Bearer test-admin-token"))
            .andExpect(status().isOk())
            // Response must not contain any of our seeded anonymousIds
            .andExpect(jsonPath("$..anonymousId").doesNotExist())
            .andExpect(content().string(not(containsString("anon-aaa-111"))))
            .andExpect(content().string(not(containsString("anon-bbb-222"))));
    }

    // Integration Test 3: Department stats filtered by department
    @Test
    @Order(3)
    @DisplayName("GET /analytics/department/{dept}: should return stats for that department only")
    void departmentStats_filteredByDepartment() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/department/Engineering")
                .header("Authorization", "Bearer test-admin-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.department").value("Engineering"));
    }

    // Integration Test 4: WireMock (promotion) was called
    @Test
    @Order(4)
    @DisplayName("Dashboard: should call promotion service for health stats")
    void dashboardCallsPromotionService() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/health-board")
                .header("Authorization", "Bearer test-admin-token"))
            .andExpect(status().isOk());

        verify(getRequestedFor(urlPathEqualTo("/api/v1/health-status/stats")));
    }

    // Integration Test 5: Analytics endpoint responds in < 500ms
    @Test
    @Order(5)
    @DisplayName("GET /analytics/health-board: should respond within 500ms SLA")
    void healthBoard_responseTime_under500ms() throws Exception {
        long start = System.currentTimeMillis();

        mockMvc.perform(get("/api/v1/analytics/health-board")
                .header("Authorization", "Bearer test-admin-token"))
            .andExpect(status().isOk());

        long elapsed = System.currentTimeMillis() - start;
        Assertions.assertTrue(elapsed < 500,
            "Response time " + elapsed + "ms exceeds 500ms SLA");
    }
}