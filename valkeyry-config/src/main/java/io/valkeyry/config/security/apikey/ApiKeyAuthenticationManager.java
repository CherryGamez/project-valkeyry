package io.valkeyry.config.security.apikey;

import io.valkeyry.config.config.ApiKeyProperties;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Verifies the supplied API-key against the {@code valkeyry.api-keys.table} mapping.
 *
 * <p>Successful authentication produces a principal whose name is the encoded key (truncated
 * for safety in logs) and a set of {@code SCOPE_tenant:&lt;id&gt;} authorities mirroring the LDAP
 * pathway, so the downstream {@link io.valkeyry.config.security.TenantAccessGuard} works
 * identically for both flows.</p>
 */
public class ApiKeyAuthenticationManager implements ReactiveAuthenticationManager {

    private final ApiKeyProperties props;

    public ApiKeyAuthenticationManager(ApiKeyProperties props) {
        this.props = props;
    }

    @Override
    public Mono<Authentication> authenticate(Authentication authentication) {
        String key = String.valueOf(authentication.getCredentials());
        Map<String, List<String>> table = props.parsed();
        List<String> tenants = table.get(key);
        if (tenants == null || tenants.isEmpty()) {
            return Mono.error(new BadCredentialsException("Unknown API key"));
        }
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_API_KEY"));
        authorities.add(new SimpleGrantedAuthority("ROLE_VALKEYRY_WRITER"));
        for (String t : tenants) authorities.add(new SimpleGrantedAuthority("SCOPE_tenant:" + t));
        String principalName = "api-key:" + (key.length() > 8 ? key.substring(0, 8) + "…" : key);
        return Mono.just(UsernamePasswordAuthenticationToken.authenticated(principalName, "<erased>", authorities));
    }
}
