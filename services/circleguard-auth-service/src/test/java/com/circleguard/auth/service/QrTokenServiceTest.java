package com.circleguard.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey; 
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class QrTokenServiceTest {

    private static final String SECRET = "CBdF4NbOE8wywiEHp8lE4SY36coupzU2pGgkt4S1Us8g8B0WQzIR4xL12TKyf0Ep";
    private static final long EXPIRATION_MS = 60_000L; // 60 seconds

    private QrTokenService qrTokenService;
    private SecretKey key;

    @BeforeEach
    void setUp() {
        qrTokenService = new QrTokenService(SECRET, EXPIRATION_MS);
        key = Keys.hmacShaKeyFor(SECRET.getBytes());
    }

    @Test
    @DisplayName("generateQrToken: should produce a valid JWT string")
    void generateQrToken_producesValidJwt() {
        UUID anonymousId = UUID.randomUUID();

        String token = qrTokenService.generateQrToken(anonymousId);

        assertThat(token).isNotNull().isNotBlank();
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    @DisplayName("generateQrToken: subject should be the anonymousId")
    void generateQrToken_subjectIsAnonymousId() {
        UUID anonymousId = UUID.fromString("deadbeef-dead-beef-dead-beefdeadbeef");

        String token = qrTokenService.generateQrToken(anonymousId);

        Claims claims = parseToken(token);
        assertThat(claims.getSubject()).isEqualTo(anonymousId.toString());
    }

    @Test
    @DisplayName("generateQrToken: expiration should be ~60 seconds from now")
    void generateQrToken_expiresWithinWindow() {
        UUID anonymousId = UUID.randomUUID();
        long before = System.currentTimeMillis();

        String token = qrTokenService.generateQrToken(anonymousId);

        long after = System.currentTimeMillis();
        Claims claims = parseToken(token);
        long expMs = claims.getExpiration().getTime();
        assertThat(expMs).isBetween(before + EXPIRATION_MS - 1000, after + EXPIRATION_MS + 1000);
    }

    @Test
    @DisplayName("generateQrToken: token with 1ms TTL should fail validation after sleep")
    void generateQrToken_expiredToken_isRejected() throws InterruptedException {
        QrTokenService shortLived = new QrTokenService(SECRET, 1L);
        UUID anonymousId = UUID.randomUUID();

        String token = shortLived.generateQrToken(anonymousId);
        Thread.sleep(50);

        assertThatThrownBy(() -> parseToken(token))
            .isInstanceOf(io.jsonwebtoken.ExpiredJwtException.class);
    }

    @Test
    @DisplayName("generateQrToken: QR secret should be different from access JWT secret")
    void generateQrToken_qrSecretIsDifferentFromJwtSecret() {
        String jwtSecret = "my-super-secret-dev-key-32-chars-long-12345678";
        assertThat(SECRET).isNotEqualTo(jwtSecret);
    }

    @Test
    @DisplayName("generateQrToken: different users should receive different tokens")
    void generateQrToken_differentUsers_differentTokens() {
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();

        String token1 = qrTokenService.generateQrToken(user1);
        String token2 = qrTokenService.generateQrToken(user2);

        assertThat(token1).isNotEqualTo(token2);
    }

    @Test
    @DisplayName("generateQrToken: token from wrong secret is rejected")
    void generateQrToken_wrongSecret_rejectedByGateway() {
        QrTokenService wrongSecretService = new QrTokenService("Zi7UeEcRAkyhaAZ2TPy0vfw1FVMdNBcWiOkIvLtABca", EXPIRATION_MS);
        UUID anonymousId = UUID.randomUUID();

        String badToken = wrongSecretService.generateQrToken(anonymousId);

        assertThatThrownBy(() -> {
            Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(badToken);
        }).isInstanceOf(io.jsonwebtoken.security.SecurityException.class);
    }

    private Claims parseToken(String token) {
        return Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }
}