package com.circleguard.gateway.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.Key;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for QrValidationService (gateway).
 * Validates token parsing, health status checks, and access decisions.
 *
 * SECURITY: CONFIRMED/SUSPECT users must be denied campus access.
 */
@ExtendWith(MockitoExtension.class)
class QrValidationServiceTest {

    private static final String QR_SECRET = "my-qr-secret-key-for-dev-1234567890";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private QrValidationService validationService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(validationService, "qrSecret", QR_SECRET);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName("validateToken: healthy user with valid token should be granted access")
    void validateToken_validToken_healthyUser_accessGranted() {
        UUID userId = UUID.randomUUID();
        String token = buildToken(userId, 60_000L);
        when(valueOps.get("user:status:" + userId)).thenReturn(null); // No status = ACTIVE

        QrValidationService.ValidationResult result = validationService.validateToken(token);

        assertThat(result.valid()).isTrue();
        assertThat(result.status()).isEqualTo("GREEN");
        assertThat(result.message()).contains("Welcome");
    }

    @Test
    @DisplayName("validateToken: confirmed positive user should be denied campus access")
    void validateToken_confirmedUser_accessDenied() {
        UUID userId = UUID.randomUUID();
        String token = buildToken(userId, 60_000L);
        when(valueOps.get("user:status:" + userId)).thenReturn("CONTAGIED");

        QrValidationService.ValidationResult result = validationService.validateToken(token);

        assertThat(result.valid()).isFalse();
        assertThat(result.status()).isEqualTo("RED");
        assertThat(result.message()).containsIgnoringCase("denied");
    }

    @Test
    @DisplayName("validateToken: expired QR token should be rejected with RED status")
    void validateToken_expiredToken_accessDenied() throws InterruptedException {
        UUID userId = UUID.randomUUID();
        String token = buildToken(userId, 1L); // expires in 1ms
        Thread.sleep(50);

        QrValidationService.ValidationResult result = validationService.validateToken(token);

        assertThat(result.valid()).isFalse();
        assertThat(result.status()).isEqualTo("RED");
        assertThat(result.message()).containsIgnoringCase("expired").or().containsIgnoringCase("invalid");
    }

    @Test
    @DisplayName("validateToken: completely malformed token should return RED")
    void validateToken_malformedToken_returnRed() {
        String badToken = "not.a.jwt.token.at.all";

        QrValidationService.ValidationResult result = validationService.validateToken(badToken);

        assertThat(result.valid()).isFalse();
        assertThat(result.status()).isEqualTo("RED");
    }

    @Test
    @DisplayName("validateToken: token forged with wrong secret should be rejected")
    void validateToken_forgedToken_rejected() {
        String wrongSecret = "wrong-secret-key-for-testing-forgery-xxx";
        Key wrongKey = Keys.hmacShaKeyFor(wrongSecret.getBytes());
        String forgedToken = Jwts.builder()
            .setSubject(UUID.randomUUID().toString())
            .setExpiration(new Date(System.currentTimeMillis() + 60_000))
            .signWith(wrongKey, SignatureAlgorithm.HS256)
            .compact();

        QrValidationService.ValidationResult result = validationService.validateToken(forgedToken);

        assertThat(result.valid()).isFalse();
        assertThat(result.status()).isEqualTo("RED");
    }

    @Test
    @DisplayName("validateToken: POTENTIAL health risk user should be denied access")
    void validateToken_potentialRiskUser_accessDenied() {
        UUID userId = UUID.randomUUID();
        String token = buildToken(userId, 60_000L);
        when(valueOps.get("user:status:" + userId)).thenReturn("POTENTIAL");

        QrValidationService.ValidationResult result = validationService.validateToken(token);

        assertThat(result.valid()).isFalse();
        assertThat(result.status()).isEqualTo("RED");
    }

    @Test
    @DisplayName("validateToken: null/empty token should return RED safely")
    void validateToken_emptyToken_returnRed() {
        QrValidationService.ValidationResult result1 = validationService.validateToken("");
        QrValidationService.ValidationResult result2 = validationService.validateToken(null);

        assertThat(result1.valid()).isFalse();
        assertThat(result2.valid()).isFalse();
    }

    private String buildToken(UUID userId, long expirationMs) {
        Key key = Keys.hmacShaKeyFor(QR_SECRET.getBytes());
        return Jwts.builder()
            .setSubject(userId.toString())
            .setIssuedAt(new Date())
            .setExpiration(new Date(System.currentTimeMillis() + expirationMs))
            .signWith(key, SignatureAlgorithm.HS256)
            .compact();
    }
}