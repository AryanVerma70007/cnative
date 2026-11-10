package com.logistics.gateway;
import io.jsonwebtoken.*;import io.jsonwebtoken.security.Keys;import java.nio.charset.StandardCharsets;import java.util.*;import org.junit.jupiter.api.*;import static org.junit.jupiter.api.Assertions.*;import org.springframework.mock.http.server.reactive.MockServerHttpRequest;import org.springframework.mock.web.server.MockServerWebExchange;
class JwtSecurityTest {
 static final String SECRET="change-this-development-secret-32-chars-minimum";
 JwtTokenService service(){return new JwtTokenService(SECRET,"logistics-platform","logistics-api");}
 String token(Date expiry,String role){var key=Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));return Jwts.builder().subject("user").issuer("logistics-platform").audience().add("logistics-api").and().claim("role",role).expiration(expiry).signWith(key).compact();}
 @Test void roleClaimIsReadFromValidToken(){assertEquals("ADMIN",service().parse(token(new Date(System.currentTimeMillis()+60000),"ADMIN")).get("role",String.class));}
 @Test void malformedAndTamperedTokensAreRejected(){assertThrows(JwtException.class,()->service().parse("not-a-jwt"));var valid=token(new Date(System.currentTimeMillis()+60000),"CUSTOMER");assertThrows(JwtException.class,()->service().parse(valid+"x"));}
 @Test void expiredTokenIsRejected(){assertThrows(ExpiredJwtException.class,()->service().parse(token(new Date(System.currentTimeMillis()-1000),"CUSTOMER")));}
 @Test void missingBearerTokenIsUnauthorized(){var exchange=MockServerWebExchange.from(MockServerHttpRequest.get("/api/orders").build());new JwtGatewayFilter(service()).filter(exchange,e->reactor.core.publisher.Mono.empty()).block();assertEquals(401,exchange.getResponse().getStatusCode().value());}
}
