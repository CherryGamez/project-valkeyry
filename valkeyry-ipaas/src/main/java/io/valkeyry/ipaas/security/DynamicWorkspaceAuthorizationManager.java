package io.valkeyry.ipaas.security;

import io.valkeyry.ipaas.repository.UserAccessPolicyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
@Slf4j
@Component
@RequiredArgsConstructor
public class DynamicWorkspaceAuthorizationManager {

    /** Path must contain alphanumeric slugs (^[A-Za-z0-9_-]{1,200}$) for tenant + project. */
    private static final Pattern SCOPED =
            Pattern.compile("^/api/v1/([A-Za-z0-9_-]{1,200})/([A-Za-z0-9_-]{1,200})(/.*)?$");

    private final UserAccessPolicyRepository policyRepository;

    /** Required privilege based on HTTP method. */
    private String requiredPrivilege(String method) {
        return switch (method.toUpperCase()) {
            case "GET", "HEAD", "OPTIONS" -> "PROJECT_READ";
            default -> "PROJECT_WRITE";
        };
    }

    public Mono<AuthorizationDecision> check(Mono<Authentication> authMono, AuthorizationContext ctx) {
        String path = ctx.getExchange().getRequest().getPath().value();
        Matcher m = SCOPED.matcher(path);
        if (!m.matches()) {
            // not a tenant/project-scoped route: allow if authenticated
            return authMono.map(a -> new AuthorizationDecision(a.isAuthenticated()))
                    .defaultIfEmpty(new AuthorizationDecision(false));
        }
        final String tenantId;
        final String projectId;
        try {
            tenantId  = m.group(1);
            projectId = m.group(2);
        } catch (IllegalArgumentException ex) {
            return Mono.just(new AuthorizationDecision(false));
        }
        String privilege = requiredPrivilege(ctx.getExchange().getRequest().getMethod().name());

        return authMono.flatMap(auth -> {
            if (!(auth.getPrincipal() instanceof Jwt jwt)) {
                return Mono.just(new AuthorizationDecision(false));
            }
            String subject = jwt.getSubject();
            return policyRepository.findBySubjectAndTenantIdAndProjectIdAndPrivilege(
                            subject, tenantId, projectId, privilege)
                    .map(p -> new AuthorizationDecision(true))
                    .defaultIfEmpty(new AuthorizationDecision(false));
        }).defaultIfEmpty(new AuthorizationDecision(false));
    }
}
