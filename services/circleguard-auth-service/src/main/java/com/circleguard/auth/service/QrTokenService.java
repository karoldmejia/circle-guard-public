package com.circleguard.auth.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.security.Key;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

@Service
public class QrTokenService {
    private final SecretKey key;
    private final long expiration;

    public QrTokenService(@Value("${qr.secret}") String secret, 
                         @Value("${qr.expiration:60000}") long expiration) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes());
        this.expiration = expiration;
    }

    public String generateQrToken(UUID anonymousId) {
        return Jwts.builder()
                .setSubject(anonymousId.toString())
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }
}
