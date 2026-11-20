package com.policylens.gateway.auth;

import com.policylens.gateway.domain.Tenant;
import com.policylens.gateway.domain.User;
import com.policylens.gateway.repository.TenantRepository;
import com.policylens.gateway.repository.UserRepository;
import com.policylens.gateway.security.JwtService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Exposes account registration and login endpoints. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final TenantRepository tenants;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(TenantRepository tenants, UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.tenants = tenants; this.users = users; this.passwordEncoder = passwordEncoder; this.jwtService = jwtService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        String email = request.email().trim().toLowerCase();
        if (users.existsByEmail(email)) throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists");
        String tenantName = request.tenantName().trim();
        if (tenantName.isBlank()) throw new IllegalArgumentException("Tenant name must not be blank");
        Tenant tenant = tenants.save(new Tenant(tenantName));
        User user = users.save(new User(email, passwordEncoder.encode(request.password()), tenant));
        return new AuthResponse(jwtService.createToken(user), user.getId().toString(), tenant.getId().toString());
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        String email = request.email().trim().toLowerCase();
        User user = users.findByEmail(email).orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) throw new BadCredentialsException("Invalid credentials");
        return new AuthResponse(jwtService.createToken(user), user.getId().toString(), user.getTenant().getId().toString());
    }

    public record RegisterRequest(@NotBlank @Email String email,
                                  @NotBlank @Size(min = 8, max = 72) String password,
                                  @NotBlank @Size(max = 160) String tenantName) { }
    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) { }
    public record AuthResponse(String token, String userId, String tenantId) { }
}
