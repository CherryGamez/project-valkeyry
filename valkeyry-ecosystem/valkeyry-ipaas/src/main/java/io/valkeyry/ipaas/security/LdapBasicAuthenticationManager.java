package io.valkeyry.ipaas.security;

import io.valkeyry.ipaas.config.LdapProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.ldap.query.LdapQueryBuilder;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Mirror of the {@code valkeyry-config} LDAP manager — bridged to a reactive boundary. */
public class LdapBasicAuthenticationManager implements ReactiveAuthenticationManager {

    private static final Logger log = LoggerFactory.getLogger(LdapBasicAuthenticationManager.class);
    private final LdapProperties props;

    public LdapBasicAuthenticationManager(LdapProperties props) { this.props = props; }

    @Override
    public Mono<Authentication> authenticate(Authentication authentication) {
        String username = String.valueOf(authentication.getPrincipal());
        String password = String.valueOf(authentication.getCredentials());
        if (username.isBlank() || password.isBlank()) {
            return Mono.error(new BadCredentialsException("Missing credentials"));
        }
        return Mono.fromCallable(() -> bind(username, password))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(ex -> new BadCredentialsException("LDAP bind failed", ex));
    }

    private Authentication bind(String username, String password) throws javax.naming.NamingException {
        LdapContextSource ctx = new LdapContextSource();
        ctx.setUrl(props.getUrl());
        ctx.setBase(props.getBaseDn());
        ctx.setUserDn(props.getUserDnPattern().replace("{0}", username));
        ctx.setPassword(password);
        ctx.afterPropertiesSet();
        ctx.getContext(ctx.getUserDn(), password).close();

        LdapContextSource mgr = new LdapContextSource();
        mgr.setUrl(props.getUrl());
        mgr.setBase(props.getBaseDn());
        if (!props.getManagerDn().isBlank()) {
            mgr.setUserDn(props.getManagerDn());
            mgr.setPassword(props.getManagerPassword());
        }
        mgr.afterPropertiesSet();
        LdapTemplate template = new LdapTemplate(mgr);
        List<List<String>> tenants = template.search(
                LdapQueryBuilder.query().where("uid").is(username),
                new TenantMapper(props.getTenantAttribute()));
        Set<String> uniqueTenants = new HashSet<>();
        for (List<String> sub : tenants) uniqueTenants.addAll(sub);

        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_LDAP_AGENT"));
        for (String t : uniqueTenants) authorities.add(new SimpleGrantedAuthority("SCOPE_tenant:" + t));
        log.debug("LDAP bind ok for {} → tenants={}", username, uniqueTenants);
        return UsernamePasswordAuthenticationToken.authenticated(username, "<erased>", authorities);
    }

    private static final class TenantMapper implements AttributesMapper<List<String>> {
        private final String name;
        TenantMapper(String name) { this.name = name; }
        @Override public List<String> mapFromAttributes(Attributes attributes) throws javax.naming.NamingException {
            Attribute a = attributes.get(name);
            if (a == null) return List.of();
            List<String> out = new ArrayList<>(a.size());
            for (int i = 0; i < a.size(); i++) out.add(String.valueOf(a.get(i)));
            return out;
        }
    }
}
