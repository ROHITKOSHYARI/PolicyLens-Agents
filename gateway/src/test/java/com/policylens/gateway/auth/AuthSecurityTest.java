package com.policylens.gateway.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.policylens.gateway.domain.Tenant;
import com.policylens.gateway.domain.User;
import com.policylens.gateway.repository.TenantRepository;
import com.policylens.gateway.repository.UserRepository;
import com.policylens.gateway.security.JwtService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Tests public auth routes, protected routes, and tenant-bound JWT claims. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired TenantRepository tenants;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtService jwtService;

    @Test
    void registerCreatesTenantAndLoginReturnsTenantClaims() throws Exception {
        String email = "person-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"correct-horse-battery","tenantName":"Acme %s"}
                                """.formatted(email, UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").isNotEmpty());

        String response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"correct-horse-battery"}
                                """.formatted(email)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(response).get("token").asText();
        var claims = jwtService.parse(token);
        User user = users.findByEmail(email).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(user.getId().toString(), claims.get("userId", String.class));
        org.junit.jupiter.api.Assertions.assertEquals(user.getTenant().getId().toString(), claims.get("tenantId", String.class));
        org.junit.jupiter.api.Assertions.assertTrue(user.getPasswordHash().startsWith("$2a$"));
    }

    @Test
    void protectedRoutesRequireTokenAndErrorsHaveConsistentShape() throws Exception {
        mvc.perform(get("/api/documents"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", equalTo(401)))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void rejectsTamperedAndExpiredTokens() throws Exception {
        Tenant tenant = tenants.save(new Tenant("Security " + UUID.randomUUID()));
        User user = users.save(new User("security-" + UUID.randomUUID() + "@example.test", encoder.encode("password123"), tenant));
        String good = jwtService.createToken(user);
        String tampered = good.substring(0, good.length() - 1) + (good.endsWith("a") ? "b" : "a");
        mvc.perform(get("/api/documents").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status", equalTo(401)));

        String expired = Jwts.builder().subject(user.getEmail()).claim("userId", user.getId().toString())
                .claim("tenantId", tenant.getId().toString()).issuedAt(Date.from(Instant.now().minusSeconds(120)))
                .expiration(Date.from(Instant.now().minusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor("test-secret-that-is-longer-than-thirty-two-bytes".getBytes(StandardCharsets.UTF_8))).compact();
        mvc.perform(get("/api/documents").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Invalid or expired bearer token"));
    }
}
