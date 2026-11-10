package com.logistics.notification;

import io.jsonwebtoken.*;import io.jsonwebtoken.security.Keys;import java.nio.charset.StandardCharsets;import java.time.Instant;import java.util.*;import javax.crypto.SecretKey;import org.springframework.beans.factory.annotation.Value;import org.springframework.stereotype.Service;
@Service public class JwtTokenService {
 private final SecretKey key; private final String issuer,audience; private final long expiration;
 public JwtTokenService(@Value("${app.jwt.secret}") String secret,@Value("${app.jwt.issuer}") String issuer,@Value("${app.jwt.audience}") String audience,@Value("${app.jwt.expiration-seconds:3600}") long expiration){if(secret.length()<32)throw new IllegalArgumentException("JWT secret must be at least 256 bits");this.key=Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));this.issuer=issuer;this.audience=audience;this.expiration=expiration;}
 public Claims parse(String token){return Jwts.parser().verifyWith(key).requireIssuer(issuer).requireAudience(audience).build().parseSignedClaims(token).getPayload();}
 public String create(String subject,String role){Instant now=Instant.now();return Jwts.builder().subject(subject).issuer(issuer).audience().add(audience).and().claim("role",role).issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(expiration))).signWith(key).compact();}
}

