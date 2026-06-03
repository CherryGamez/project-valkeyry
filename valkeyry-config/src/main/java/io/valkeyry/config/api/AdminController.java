package io.valkeyry.config.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.valkeyry.config.api.admin.TenantUpsertRequest;
import io.valkeyry.config.api.admin.TenantView;
import io.valkeyry.config.api.admin.UserUpsertRequest;
import io.valkeyry.config.api.admin.UserView;
import io.valkeyry.config.domain.admin.AdminTenant;
import io.valkeyry.config.domain.admin.AppUser;
import io.valkeyry.config.domain.admin.AppUserTenant;
import io.valkeyry.config.repo.AdminTenantRepository;
import io.valkeyry.config.repo.AppUserRepository;
import io.valkeyry.config.repo.AppUserTenantRepository;
import jakarta.validation.Valid;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Tenant + user CRUD for the HTMX admin panel.
 *
 * <p>Every route requires the bearer JWT to carry the {@code admin} role
 * ({@code SCOPE_admin} after the {@link io.valkeyry.config.security.oidc.JwtTenantAuthoritiesConverter}
 * maps it). The class-level {@link PreAuthorize} enforces that admin-only gate.</p>
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAuthority('SCOPE_admin')")
@Tag(name = "Admin", description = "Tenant & user administration (Camunda-Identity style).")
public class AdminController {

    private final AdminTenantRepository tenants;
    private final AppUserRepository users;
    private final AppUserTenantRepository userTenants;
    private final PasswordEncoder encoder;

    public AdminController(AdminTenantRepository tenants,
                           AppUserRepository users,
                           AppUserTenantRepository userTenants,
                           PasswordEncoder encoder) {
        this.tenants = tenants;
        this.users = users;
        this.userTenants = userTenants;
        this.encoder = encoder;
    }

    // ----------------------- Tenants -----------------------

    @GetMapping("/tenants")
    @Operation(summary = "List all admin-managed tenants")
    public Flux<TenantView> listTenants() {
        return tenants.listAll().map(this::toTenantView);
    }

    @PostMapping("/tenants")
    @Operation(summary = "Create an admin-managed tenant")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Created"),
        @ApiResponse(responseCode = "409", description = "A tenant with that id already exists")
    })
    public Mono<ResponseEntity<TenantView>> createTenant(@Valid @RequestBody TenantUpsertRequest req,
                                                         Authentication auth) {
        AdminTenant t = new AdminTenant();
        t.setId(req.id());
        t.setName(req.name());
        t.setDescription(req.description());
        t.setEnabled(req.enabled() == null ? true : req.enabled());
        t.setCreatedAt(Instant.now());
        t.setCreatedBy(auth.getName());
        return tenants.save(t)
                .map(saved -> ResponseEntity.status(HttpStatus.CREATED).body(toTenantView(saved)))
                .onErrorMap(DuplicateKeyException.class,
                        e -> new ResponseStatusException(HttpStatus.CONFLICT, "Tenant id already exists"));
    }

    @PutMapping("/tenants/{id}")
    @Operation(summary = "Replace metadata of an existing tenant (id is immutable)")
    public Mono<TenantView> updateTenant(@PathVariable String id,
                                         @Valid @RequestBody TenantUpsertRequest req) {
        if (!Objects.equals(id, req.id())) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path id must match body id"));
        }
        return tenants.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "No such tenant")))
                .flatMap(existing -> {
                    existing.setName(req.name());
                    existing.setDescription(req.description());
                    if (req.enabled() != null) existing.setEnabled(req.enabled());
                    existing.markPersisted();
                    return tenants.save(existing);
                })
                .map(this::toTenantView);
    }

    @DeleteMapping("/tenants/{id}")
    @Operation(summary = "Delete a tenant", description = "Cascades to its user_tenant rows; registry data under that tenant is left untouched.")
    public Mono<ResponseEntity<Void>> deleteTenant(@PathVariable String id) {
        return tenants.deleteById(id).then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    private TenantView toTenantView(AdminTenant t) {
        return new TenantView(t.getId(), t.getName(), t.getDescription(), t.isEnabled(),
                t.getCreatedAt(), t.getCreatedBy());
    }

    // ----------------------- Users -----------------------

    @GetMapping("/users")
    @Operation(summary = "List every admin-managed user with their tenant assignments")
    public Flux<UserView> listUsers() {
        return users.listAll().concatMap(u ->
                userTenants.findByUser(u.getId())
                        .map(AppUserTenant::getTenantId)
                        .collectList()
                        .map(slugs -> toUserView(u, slugs)));
    }

    @PostMapping("/users")
    @Operation(summary = "Create a user (LOCAL/LDAP/SSO)")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Created"),
        @ApiResponse(responseCode = "409", description = "Username already exists")
    })
    public Mono<ResponseEntity<UserView>> createUser(@Valid @RequestBody UserUpsertRequest req,
                                                     Authentication auth) {
        String source = req.source() == null ? "LOCAL" : req.source().toUpperCase();
        if ("LOCAL".equals(source) && (req.password() == null || req.password().isBlank())) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password required for LOCAL users"));
        }
        AppUser u = new AppUser();
        u.setId(UUID.randomUUID());
        u.setUsername(req.username());
        u.setDisplayName(req.displayName());
        u.setEmail(req.email());
        u.setPasswordHash("LOCAL".equals(source) ? encoder.encode(req.password()) : null);
        u.setRole(req.role() == null ? "reader" : req.role());
        u.setSource(source);
        u.setEnabled(req.enabled() == null ? true : req.enabled());
        u.setCreatedAt(Instant.now());
        u.setCreatedBy(auth.getName());

        return users.save(u)
                .flatMap(saved -> persistTenants(saved.getId(), req.tenants())
                        .thenReturn(toUserView(saved, req.tenants() == null ? List.of() : req.tenants())))
                .map(view -> ResponseEntity.status(HttpStatus.CREATED).body(view))
                .onErrorMap(DuplicateKeyException.class,
                        e -> new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists"));
    }

    @PutMapping("/users/{id}")
    @Operation(summary = "Update a user — role, status, tenants, optional password rotation")
    public Mono<UserView> updateUser(@PathVariable UUID id, @Valid @RequestBody UserUpsertRequest req) {
        return users.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "No such user")))
                .flatMap(u -> {
                    u.setDisplayName(req.displayName());
                    u.setEmail(req.email());
                    if (req.role() != null && !req.role().isBlank()) u.setRole(req.role());
                    if (req.source() != null && !req.source().isBlank()) u.setSource(req.source().toUpperCase());
                    if (req.enabled() != null) u.setEnabled(req.enabled());
                    if (req.password() != null && !req.password().isBlank()) {
                        u.setPasswordHash(encoder.encode(req.password()));
                    }
                    u.markPersisted();
                    return users.save(u);
                })
                .flatMap(saved -> {
                    if (req.tenants() == null) {
                        // Caller didn't touch tenants — leave the existing mapping alone.
                        return userTenants.findByUser(saved.getId())
                                .map(AppUserTenant::getTenantId).collectList()
                                .map(existing -> toUserView(saved, existing));
                    }
                    return userTenants.deleteByUser(saved.getId())
                            .then(persistTenants(saved.getId(), req.tenants()))
                            .thenReturn(toUserView(saved, req.tenants()));
                });
    }

    @DeleteMapping("/users/{id}")
    public Mono<ResponseEntity<Void>> deleteUser(@PathVariable UUID id) {
        return users.deleteById(id).then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    private Mono<Void> persistTenants(UUID userId, List<String> slugs) {
        if (slugs == null || slugs.isEmpty()) return Mono.empty();
        List<AppUserTenant> rows = new ArrayList<>();
        for (String t : slugs) if (t != null && !t.isBlank()) rows.add(new AppUserTenant(userId, t.trim()));
        return userTenants.saveAll(rows).then();
    }

    private UserView toUserView(AppUser u, List<String> tenantSlugs) {
        return new UserView(u.getId(), u.getUsername(), u.getDisplayName(), u.getEmail(),
                u.getRole(), u.getSource(), u.isEnabled(),
                tenantSlugs == null ? List.of() : List.copyOf(tenantSlugs),
                u.getCreatedAt(), u.getCreatedBy());
    }
}
