package com.circleguard.gateway.integration;

import com.circleguard.gateway.GatewayServiceApplication;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.security.Key;
import java.util.Date;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import javax.crypto.SecretKey;

/**
 * Integration tests for Gateway service.
 * Validates QR token validation with real Redis (Testcontainer) and mocked auth.
 *
 * SECURITY REQUIREMENTS:
 *  - CONFIRMED/POTENTIAL users denied even with valid token
 *  - Expired tokens always rejected
 *  - Forged tokens always rejected
 */
@SpringBootTest(classes = GatewayServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GatewayRedisIntegrationTest {

    private static final String QR_SECRET = "my-qr-secret-key-for-dev-1234567890";

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.2"))
        .withExposedPorts(6379);

    private static WireMockServer wireMock;

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("qr.secret", () -> QR_SECRET);
    }

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(8099);
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private StringRedisTemplate redisTemplate;

    //  Integration Test 1: Valid token and healthy user, then access granted 
    @Test
    @Order(1)
    @DisplayName("POST /gate/validate: healthy user with valid QR token gets GREEN access")
    void validate_validToken_healthyUser_grantedAccess() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = buildQrToken(userId, 60_000L);
        // No Redis entry = ACTIVE/healthy user

        mockMvc.perform(post("/api/v1/gate/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.valid").value(true))
            .andExpect(jsonPath("$.status").value("GREEN"));
    }

    // Integration Test 2: Valid token and CONTAGIED status, then denied 
    @Test
    @Order(2)
    @DisplayName("POST /gate/validate: CONTAGIED user with valid token gets RED access denied")
    void validate_validToken_contagiedUser_deniedAccess() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = buildQrToken(userId, 60_000L);

        // Pre-seed Redis with CONTAGIED status
        redisTemplate.opsForValue().set("user:status:" + userId, "CONTAGIED");

        mockMvc.perform(post("/api/v1/gate/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.valid").value(false))
            .andExpect(jsonPath("$.status").value("RED"));
    }

    //  Integration Test 3: Expired token, then denied 
    @Test
    @Order(3)
    @DisplayName("POST /gate/validate: expired QR token should return RED")
    void validate_expiredToken_denied() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = buildQrToken(userId, -1000L); // already expired

        mockMvc.perform(post("/api/v1/gate/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.valid").value(false))
            .andExpect(jsonPath("$.status").value("RED"));
    }

    //  Integration Test 4: Redis status persists across requests 
    @Test
    @Order(4)
    @DisplayName("Redis: user status set before request should persist and affect access")
    void redis_statusPersists_affectsMultipleRequests() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = buildQrToken(userId, 60_000L);
        redisTemplate.opsForValue().set("user:status:" + userId, "POTENTIAL");

        // First request – denied
        mockMvc.perform(post("/api/v1/gate/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
            .andExpect(jsonPath("$.valid").value(false));

        // Second request with same status – still denied
        mockMvc.perform(post("/api/v1/gate/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
            .andExpect(jsonPath("$.valid").value(false));
    }

    // Integration Test 5: Forged token (wrong secret), then denied 
    @Test
    @Order(5)
    @DisplayName("POST /gate/validate: token signed with wrong secret should be rejected")
    void validate_forgedToken_denied() throws Exception {
        UUID userId = UUID.randomUUID();
        Key wrongKey = Keys.hmacShaKeyFor("wrong-secret-for-forgery-test-xxxx".getBytes());
        String forgedToken = Jwts.builder()
            .setSubject(userId.toString())
            .setIssuedAt(new Date())
            .setExpiration(new Date(System.currentTimeMillis() + 60_000))
            .signWith(wrongKey, SignatureAlgorithm.HS256)
            .compact();

        mockMvc.perform(post("/api/v1/gate/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + forgedToken + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.valid").value(false))
            .andExpect(jsonPath("$.status").value("RED"));
    }

    //  Helper 
    private String buildQrToken(UUID userId, long expirationMs) {
        SecretKey key = Keys.hmacShaKeyFor(QR_SECRET.getBytes());
        return Jwts.builder()
            .setSubject(userId.toString())
            .setIssuedAt(new Date())
            .setExpiration(new Date(System.currentTimeMillis() + expirationMs))
            .signWith(key, SignatureAlgorithm.HS256)
            .compact();
    }
}