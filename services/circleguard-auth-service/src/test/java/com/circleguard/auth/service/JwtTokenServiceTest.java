package com.circleguard.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.security.Key;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for JwtTokenService.
 * Validates token generation, claims content, expiration and security.
 *
 * KEY REQUIREMENT: JWT subject MUST be anonymousId (UUID), NOT the real username.
 */
class JwtTokenServiceTest {

    private static final String SECRET = "my-super-secret-dev-key-32-chars-long-12345678";
    private static final long EXPIRATION_MS = 3_600_000L; // 1 hour

    private JwtTokenService jwtTokenService;
    private Key key;

    @BeforeEach
    void setUp() {
        jwtTokenService = new JwtTokenService(SECRET, EXPIRATION_MS);
        key = Keys.hmacShaKeyFor(SECRET.getBytes());
    }

    @Test
    @DisplayName("generateToken: should produce a non-null, parseable JWT")
    void generateToken_producesValidJwt() {
        UUID anonymousId = UUID.randomUUID();
        Authentication auth = mockAuthentication(List.of("ROLE_STUDENT"));

        String token = jwtTokenService.generateToken(anonymousId, auth);

        assertThat(token).isNotNull().isNotBlank();
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    @DisplayName("generateToken: subject must be anonymousId UUID, not real username")
    void generateToken_subjectIsAnonymousId_notRealUsername() {
        UUID anonymousId = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
        Authentication auth = mockAuthentication(List.of("ROLE_STUDENT"));

        String token = jwtTokenService.generateToken(anonymousId, auth);

        Claims claims = parseClaims(token);
        assertThat(claims.getSubject()).isEqualTo(anonymousId.toString());
        assertThat(claims.getSubject()).doesNotContain("@").doesNotContain("student").doesNotContain("user");
    }

    @Test
    @DisplayName("generateToken: permissions claim should include all granted authorities")
    void generateToken_containsCorrectPermissions() {
        UUID anonymousId = UUID.randomUUID();
        List<String> roles = List.of("ROLE_STUDENT", "ROLE_HEALTH_CENTER");
        Authentication auth = mockAuthentication(roles);

        String token = jwtTokenService.generateToken(anonymousId, auth);

        Claims claims = parseClaims(token);
        @SuppressWarnings("unchecked")
        List<String> permissions = (List<String>) claims.get("permissions");
        assertThat(permissions).containsExactlyInAnyOrderElementsOf(roles);
    }

    @Test
    @DisplayName("generateToken: expiration time should match configured value")
    void generateToken_expirationMatchesConfiguration() {
        UUID anonymousId = UUID.randomUUID();
        Authentication auth = mockAuthentication(List.of("ROLE_STUDENT"));
        long before = System.currentTimeMillis();

        String token = jwtTokenService.generateToken(anonymousId, auth);

        long after = System.currentTimeMillis();
        Claims claims = parseClaims(token);
        long expMs = claims.getExpiration().getTime();
        assertThat(expMs).isBetween(before + EXPIRATION_MS - 2000, after + EXPIRATION_MS + 2000);
    }

    @Test
    @DisplayName("generateToken: token with 1ms expiration should be expired immediately")
    void generateToken_withVeryShortExpiration_tokenIsExpired() throws InterruptedException {
        JwtTokenService shortLivedService = new JwtTokenService(SECRET, 1L); // 1ms
        UUID anonymousId = UUID.randomUUID();
        Authentication auth = mockAuthentication(List.of("ROLE_STUDENT"));

        String token = shortLivedService.generateToken(anonymousId, auth);
        Thread.sleep(50); // wait for expiry

        assertThatThrownBy(() -> parseClaims(token))
            .isInstanceOf(io.jsonwebtoken.ExpiredJwtException.class)
            .hasMessageContaining("JWT expired");
    }

    @Test
    @DisplayName("generateToken: different anonymousIds should produce distinct tokens")
    void generateToken_differentAnonymousIds_produceDifferentTokens() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        Authentication auth = mockAuthentication(List.of("ROLE_STUDENT"));

        String token1 = jwtTokenService.generateToken(id1, auth);
        String token2 = jwtTokenService.generateToken(id2, auth);

        assertThat(token1).isNotEqualTo(token2);
    }

    @Test
    @DisplayName("generateToken: token signed with different secret should fail validation")
    void generateToken_wrongSecret_failsValidation() {
        JwtTokenService tampered = new JwtTokenService("wrong-secret-key-xxxxxxxxxxxxxxxxxxxxxxxx", EXPIRATION_MS);
        UUID anonymousId = UUID.randomUUID();
        Authentication auth = mockAuthentication(List.of("ROLE_STUDENT"));

        String tamperedToken = tampered.generateToken(anonymousId, auth);

        // Parsing with original key should fail
        Key originalKey = Keys.hmacShaKeyFor(SECRET.getBytes());
        assertThatThrownBy(() ->
            Jwts.parserBuilder().setSigningKey(originalKey).build()
                .parseClaimsJws(tamperedToken)
        ).isInstanceOf(io.jsonwebtoken.security.SecurityException.class);
    }

    private Authentication mockAuthentication(List<String> roles) {
        Authentication auth = mock(Authentication.class);
        Collection<GrantedAuthority> authorities = roles.stream()
            .map(SimpleGrantedAuthority::new)
            .map(a -> (GrantedAuthority) a)
            .toList();
        doReturn(authorities).when(auth).getAuthorities();
        return auth;
    }

    private Claims parseClaims(String token) {
        return Jwts.parserBuilder()
            .setSigningKey(key)
            .build()
            .parseClaimsJws(token)
            .getBody();
    }
}