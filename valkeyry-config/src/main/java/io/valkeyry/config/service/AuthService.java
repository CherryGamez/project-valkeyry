package io.valkeyry.config.service;

import io.valkeyry.config.api.admin.LoginResponse;
import io.valkeyry.config.config.AuthProperties;
import io.valkeyry.config.config.LdapProperties;
import io.valkeyry.config.domain.admin.AppUserTenant;
import io.valkeyry.config.repo.AppUserRepository;
import io.valkeyry.config.repo.AppUserTenantRepository;
import io.valkeyry.config.security.ldap.LdapBasicAuthenticationManager;
import io.valkeyry.config.security.local.LocalJwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves form-login credentials against the configured tracks (Local DB / LDAP / built-in
 * admin) and mints an HS256 token via {@link LocalJwtService}.
 *
 * <p>Resolution order (each step is skipped when its enabling flag is off):</p>
 * <ol>
 *   <li><b>Built-in admin bypass</b> — credentials match {@code valkeyry.auth.admin-username} /
 *       {@code admin-password}. Always available so devs can boot a fresh database.</li>
 *   <li><b>Local DB user</b> — {@code app_user} row with {@code source=LOCAL} and a BCrypt match.</li>
 *   <li><b>LDAP bind</b> — enabled when {@code valkeyry.auth.ldap-enabled=true}; reuses the existing
 *       {@link LdapBasicAuthenticationManager}.</li>
 * </ol>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AuthProperties auth;
    private final LdapProperties ldap;
    private final AppUserRepository users;
    private final AppUserTenantRepository userTenants;
    private final LocalJwtService jwt;
    private final PasswordEncoder encoder;

    public AuthService(AuthProperties auth,
                       LdapProperties ldap,
                       AppUserRepository users,
                       AppUserTenantRepository userTenants,
                       LocalJwtService jwt,
                       PasswordEncoder encoder) {
        this.auth = auth;
        this.ldap = ldap;
        this.users = users;
        this.userTenants = userTenants;
        this.jwt = jwt;
        this.encoder = encoder;
    }

    public Mono<LoginResponse> login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return Mono.error(new BadCredentialsException("Missing credentials"));
        }
        // 1) Built-in admin bypass — wildcard tenant access.
        if (username.equals(auth.getAdminUsername()) && password.equals(auth.getAdminPassword())) {
            log.info("Built-in admin login: {}", username);
            return Mono.just(buildResponse(username, "admin", List.of("*"), "LOCAL"));
        }
        // 2) Local DB user
        return users.findByUsername(username)
                .flatMap(u -> {
                    if (!u.isEnabled()) return Mono.error(new BadCredentialsException("User disabled"));
                    if (!"LOCAL".equalsIgnoreCase(u.getSource())) {
                        // Local password check only makes sense for LOCAL entries — skip to LDAP if enabled.
                        return Mono.empty();
                    }
                    if (u.getPasswordHash() == null || !encoder.matches(password, u.getPasswordHash())) {
                        return Mono.error(new BadCredentialsException("Bad password"));
                    }
                    return userTenants.findByUser(u.getId())
                            .map(AppUserTenant::getTenantId)
                            .collectList()
                            .map(tenants -> buildResponse(u.getUsername(),
                                    u.getRole() == null ? "reader" : u.getRole(),
                                    tenants, "LOCAL"));
                })
                .switchIfEmpty(Mono.defer(() -> ldapLogin(username, password)));
    }

    private Mono<LoginResponse> ldapLogin(String username, String password) {
        if (!auth.isLdapEnabled()) {
            return Mono.error(new BadCredentialsException("Unknown user"));
        }
        LdapBasicAuthenticationManager mgr = new LdapBasicAuthenticationManager(ldap);
        return mgr.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username, password))
                .map(this::ldapAuthToResponse);
    }

    private LoginResponse ldapAuthToResponse(Authentication auth) {
                Set<String> tenants = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("SCOPE_tenant:"))
                .map(a -> a.substring("SCOPE_tenant:".length()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        boolean writer = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_VALKEYRY_WRITER".equals(a.getAuthority()));
        return buildResponse(auth.getName(), writer ? "writer" : "reader",
                List.copyOf(tenants), "LDAP");
    }

    private LoginResponse buildResponse(String username, String role, List<String> tenants, String source) {
        String token = jwt.mint(username, role, tenants, source);
        String mode = "admin".equalsIgnoreCase(role) ? "admin" : "console";
        return new LoginResponse(token, "Bearer", auth.getJwtTtlSeconds(), username, role, tenants, source, mode);
    }
}
