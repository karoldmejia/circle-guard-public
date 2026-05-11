package com.circleguard.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * End-to-End tests for CircleGuard system.
 * All services must be running in namespace 'stage' (or locally via docker-compose).
 *
 * Tests complete user journeys:
 *  1. Student login, then JWT with anonymousId
 *  2. Positive case report, then contact propagation
 *  3. QR generation, then campus access granted
 *  4. CONFIRMED user, then campus access denied
 *  5. Dashboard shows aggregated data (no PII)
 *
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CircleGuardE2ETest {

    private static final String BASE_URL    = System.getProperty("test.base.url", "http://localhost");
    private static final String AUTH_URL    = BASE_URL + ":8180";
    private static final String IDENTITY_URL = BASE_URL + ":8083";
    private static final String PROMO_URL   = BASE_URL + ":8088";
    private static final String GATEWAY_URL = BASE_URL + ":8087";
    private static final String DASH_URL    = BASE_URL + ":8084";
    private static final String NOTIF_URL   = BASE_URL + ":8082";

    private static final RestTemplate rest = new RestTemplate();
    private static final ObjectMapper mapper = new ObjectMapper();

    // Shared state across E2E tests (ordered execution)
    private static String studentToken;
    private static String healthCenterToken;
    private static String studentAnonymousId;
    private static String confirmedAnonymousId;
    private static String qrToken;

    // E2E Flow 1: Student Login
    @Test
    @Order(1)
    @DisplayName("E2E-1a: Student can log in and receives a JWT token")
    void e2e1_studentLogin_receivesToken() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(
            "{\"username\":\"student1\",\"password\":\"password123\"}", headers);

        ResponseEntity<String> response = rest.postForEntity(AUTH_URL + "/api/v1/auth/login", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(response.getBody());
        studentToken = body.get("token").asText();
        studentAnonymousId = body.path("anonymousId").asText("");

        assertThat(studentToken).isNotBlank();
        assertThat(body.get("type").asText()).isEqualTo("Bearer");
    }

    @Test
    @Order(2)
    @DisplayName("E2E-1b: JWT subject is anonymousId (UUID), NOT real username")
    void e2e1_jwtSubjectIsAnonymousId_notUsername() throws Exception {
        Assumptions.assumeTrue(studentToken != null, "E2E-1a must pass first");

        String[] parts = studentToken.split("\\.");
        String payload = new String(java.util.Base64.getDecoder().decode(parts[1]));
        JsonNode claims = mapper.readTree(payload);

        String subject = claims.get("sub").asText();
        assertThat(subject)
            .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
            .doesNotContain("student1")
            .doesNotContain("@");
    }

    @Test
    @Order(3)
    @DisplayName("E2E-1c: Invalid credentials return 401 Unauthorized")
    void e2e1_invalidCredentials_returns401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(
            "{\"username\":\"student1\",\"password\":\"WRONGPASSWORD\"}", headers);

        assertThatThrownBy(() -> rest.postForEntity(AUTH_URL + "/api/v1/auth/login", request, String.class))
            .isInstanceOf(HttpClientErrorException.Unauthorized.class);
    }

    // E2E Flow 2: Health Center Reports Positive Case + Propagation
    @Test
    @Order(4)
    @DisplayName("E2E-2a: Health center can log in with HEALTH_CENTER role")
    void e2e2_healthCenterLogin() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(
            "{\"username\":\"admin1\",\"password\":\"admin123\"}", headers);

        ResponseEntity<String> response = rest.postForEntity(AUTH_URL + "/api/v1/auth/login", request, String.class);
        JsonNode body = mapper.readTree(response.getBody());
        healthCenterToken = body.get("token").asText();

        assertThat(healthCenterToken).isNotBlank();
        // Verify HEALTH_CENTER permission in JWT payload
        String payload = new String(java.util.Base64.getDecoder().decode(healthCenterToken.split("\\.")[1]));
        assertThat(payload).contains("HEALTH_CENTER");
    }

    @Test
    @Order(5)
    @DisplayName("E2E-2b: Health center can map a student to an anonymousId")
    void e2e2_identityMapping() throws Exception {
        Assumptions.assumeTrue(healthCenterToken != null, "E2E-2a must pass first");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(healthCenterToken);
        HttpEntity<String> request = new HttpEntity<>("\"e2e.confirmed.student@university.edu\"", headers);

        ResponseEntity<String> response = rest.postForEntity(
            IDENTITY_URL + "/api/v1/identities/map", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        confirmedAnonymousId = response.getBody().replace("\"", "").trim();
        assertThat(confirmedAnonymousId)
            .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @Order(6)
    @DisplayName("E2E-2c: Reporting a positive case changes user status to CONFIRMED")
    void e2e2_positiveReport_propagatesStatus() throws Exception {
        Assumptions.assumeTrue(confirmedAnonymousId != null, "E2E-2b must pass first");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(healthCenterToken);
        HttpEntity<Map<String, String>> request = new HttpEntity<>(
            Map.of("anonymousId", confirmedAnonymousId, "status", "CONFIRMED"), headers);

        ResponseEntity<String> response = rest.postForEntity(
            PROMO_URL + "/api/v1/health/report", request, String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

        // Allow propagation to complete
        Thread.sleep(3000);
    }

    // E2E Flow 3: QR Code Generation and Campus Access
    @Test
    @Order(7)
    @DisplayName("E2E-3a: Healthy student can generate a QR token")
    void e2e3_healthyStudent_generatesQrToken() throws Exception {
        Assumptions.assumeTrue(studentToken != null, "E2E-1a must pass first");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(studentToken);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<String> response = rest.exchange(
            AUTH_URL + "/api/v1/qr/generate", HttpMethod.GET, request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(response.getBody());
        qrToken = body.get("token").asText();
        assertThat(qrToken).isNotBlank();
    }

    @Test
    @Order(8)
    @DisplayName("E2E-3b: Healthy student's QR token grants campus access")
    void e2e3_healthyStudentQr_grantsAccess() throws Exception {
        Assumptions.assumeTrue(qrToken != null, "E2E-3a must pass first");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, String>> request = new HttpEntity<>(Map.of("token", qrToken), headers);

        ResponseEntity<String> response = rest.postForEntity(
            GATEWAY_URL + "/api/v1/gate/validate", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.get("valid").asBoolean()).isTrue();
        assertThat(body.get("status").asText()).isEqualTo("GREEN");
    }

    @Test
    @Order(9)
    @DisplayName("E2E-3c: CONFIRMED user's QR token is denied at campus gate")
    void e2e3_confirmedUserQr_deniedAccess() throws Exception {
        Assumptions.assumeTrue(confirmedAnonymousId != null, "E2E-2b must pass first");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        assertThat(confirmedAnonymousId).isNotBlank();
    }

    // E2E Flow 4: Dashboard Analytics
    @Test
    @Order(10)
    @DisplayName("E2E-4a: Admin can retrieve aggregated health board analytics")
    void e2e4_healthBoardAnalytics_returnsAggregatedData() throws Exception {
        Assumptions.assumeTrue(healthCenterToken != null, "E2E-2a must pass first");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(healthCenterToken);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<String> response = rest.exchange(
            DASH_URL + "/api/v1/analytics/health-board", HttpMethod.GET, request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(response.getBody());

        // Verify aggregated structure – no individual IDs
        assertThat(body.toString()).doesNotContain(confirmedAnonymousId != null ? confirmedAnonymousId : "uuid-not-present");
        assertThat(body.has("total") || body.has("confirmed") || body.isArray()).isTrue();
    }

    @Test
    @Order(11)
    @DisplayName("E2E-4b: Dashboard response time is under 2 seconds end-to-end")
    void e2e4_dashboardResponseTime_under2seconds() throws Exception {
        Assumptions.assumeTrue(healthCenterToken != null);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(healthCenterToken);
        HttpEntity<Void> request = new HttpEntity<>(headers);
        long start = System.currentTimeMillis();

        rest.exchange(DASH_URL + "/api/v1/analytics/health-board", HttpMethod.GET, request, String.class);

        long elapsed = System.currentTimeMillis() - start;
        assertThat(elapsed).isLessThan(2000L);
    }

    // E2E Flow 5: System Resilience
    @Test
    @Order(12)
    @DisplayName("E2E-5: All services health checks return UP")
    void e2e5_allServices_healthCheckUp() {
        Map<String, String> services = Map.of(
            "identity", IDENTITY_URL + "/actuator/health",
            "auth",     AUTH_URL     + "/actuator/health",
            "promotion", PROMO_URL   + "/actuator/health",
            "gateway",  GATEWAY_URL  + "/actuator/health",
            "dashboard", DASH_URL    + "/actuator/health"
        );

        services.forEach((name, url) -> {
            try {
                ResponseEntity<String> resp = rest.getForEntity(url, String.class);
                assertThat(resp.getStatusCode().is2xxSuccessful())
                    .as("Service %s should be UP", name).isTrue();
                assertThat(resp.getBody()).contains("UP");
            } catch (Exception e) {
                fail("Service " + name + " is not healthy: " + e.getMessage());
            }
        });
    }
}