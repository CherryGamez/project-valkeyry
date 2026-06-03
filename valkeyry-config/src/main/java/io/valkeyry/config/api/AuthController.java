package io.valkeyry.config.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.valkeyry.config.api.admin.AuthConfigResponse;
import io.valkeyry.config.api.admin.LoginRequest;
import io.valkeyry.config.api.admin.LoginResponse;
import io.valkeyry.config.api.admin.MeResponse;
import io.valkeyry.config.config.AuthProperties;
import io.valkeyry.config.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Public auth endpoints powering the HTMX login page.
 *
 * <ul>
 *   <li>{@code GET  /api/v1/auth/config} — discover which tracks are enabled (no auth).</li>
 *   <li>{@code POST /api/v1/auth/login}  — username/password → HS256 JWT (no auth).</li>
 *   <li>{@code GET  /api/v1/auth/me}     — current principal snapshot (authenticated).</li>
 *   <li>{@code POST /api/v1/auth/logout} — client-side noop; the token is stateless.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Login, current-user introspection, and runtime auth-track discovery.")
public class AuthController {

    private final AuthService auth;
    private final AuthProperties props;
    private final String externalIssuer;

    public AuthController(AuthService auth,
                          AuthProperties props,
                          @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String externalIssuer) {
        this.auth = auth;
        this.props = props;
        this.externalIssuer = externalIssuer;
    }

    @GetMapping("/config")
    @Operation(summary = "Discover which auth tracks are wired",
               description = "Lets the login UI decide whether to show the WebSSO button and/or the username/password form.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Always returns the toggles snapshot.")})
    public Mono<AuthConfigResponse> config() {
        String authUrl = (props.isSsoEnabled() && externalIssuer != null && !externalIssuer.isBlank())
                ? externalIssuer
                : null;
        return Mono.just(new AuthConfigResponse(props.isSsoEnabled(), props.isLdapEnabled(), authUrl));
    }

    @PostMapping("/login")
    @Operation(summary = "Exchange username/password for an HS256 bearer JWT",
               description = "Tries the built-in admin bypass, then a local DB user, then LDAP if enabled.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Login OK — returns token + role + tenant list."),
        @ApiResponse(responseCode = "401", description = "Bad credentials / unknown user.")
    })
    public Mono<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return auth.login(req.username(), req.password())
                .onErrorMap(BadCredentialsException.class,
                        e -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage()));
    }

    @GetMapping("/me")
    @Operation(summary = "Inspect the current authenticated principal",
               description = "Returns the {@code valkeyry.role}, tenant list and source claim of the bearer JWT in use.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Authenticated — returns the principal."),
        @ApiResponse(responseCode = "401", description = "Missing or invalid token.")
    })
    public Mono<MeResponse> me(Authentication a) {
        if (a == null || !a.isAuthenticated()) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        }
        String role = "reader";
        String source = "LOCAL";
        List<String> tenants = List.of();
        if (a instanceof JwtAuthenticationToken jat) {
            Jwt jwt = jat.getToken();
            Object r = jwt.getClaim("valkeyry.role");
            if (r instanceof String s) role = s;
            Object src = jwt.getClaim("valkeyry.source");
            if (src instanceof String s) source = s;
            Object t = jwt.getClaim("valkeyry.tenants");
            if (t instanceof List<?> list) tenants = list.stream().map(String::valueOf).toList();
        }
        boolean writer = a.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_VALKEYRY_WRITER"::equals);
        boolean admin  = "admin".equalsIgnoreCase(role);
        return Mono.just(new MeResponse(a.getName(), role, tenants, source, writer || admin, admin));
    }

    @PostMapping("/logout")
    @Operation(summary = "Client-side logout placeholder",
               description = "Tokens are stateless — the client just discards them. Returns 204 for symmetry with form-based auth.")
    public Mono<Void> logout() { return Mono.empty(); }
}
