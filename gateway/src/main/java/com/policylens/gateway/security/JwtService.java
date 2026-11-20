package com.policylens.gateway.security;

import com.policylens.gateway.config.JwtProperties;
import com.policylens.gateway.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/** Creates and validates signed JWT bearer tokens. */
@Service
public class JwtService {
    private final JwtProperties properties;
    private final SecretKey key;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String createToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder().subject(user.getEmail()).claim("userId", user.getId().toString())
                .claim("tenantId", user.getTenant().getId().toString())
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(properties.getExpirationSeconds())))
                .signWith(key).compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    public static UUID tenantId(Claims claims) { return UUID.fromString(claims.get("tenantId", String.class)); }
    public static UUID userId(Claims claims) { return UUID.fromString(claims.get("userId", String.class)); }
}
