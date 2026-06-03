package io.valkeyry.config.security.ldap;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.Entry;
import io.valkeyry.config.config.LdapProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import reactor.test.StepVerifier;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live LDAP round-trip — boots an in-process UnboundID directory, seeds two users
 * (one with {@code ou=acme} entitlement, one with {@code ou=acme + ou=globex}), then exercises
 * the production {@link LdapBasicAuthenticationManager} against it.
 *
 * <p>Asserts:</p>
 * <ul>
 *   <li>A correct bind yields an authenticated {@link Authentication} principal.</li>
 *   <li>Authorities contain {@code SCOPE_tenant:&lt;ou&gt;} for every {@code ou} attribute.</li>
 *   <li>{@code ROLE_VALKEYRY_WRITER} is always present (technical accounts are writers).</li>
 *   <li>Bad passwords surface {@link BadCredentialsException}.</li>
 * </ul>
 *
 * <p>This is the unit-equivalent of the {@code docker compose up openldap} smoke test —
 * same code path, just driven by an embedded server so it runs in CI without Docker.</p>
 */
class LdapBasicAuthenticationManagerTest {

    private static InMemoryDirectoryServer ldap;
    private static LdapProperties props;

    @BeforeAll
    static void startEmbeddedLdap() throws Exception {
        InMemoryDirectoryServerConfig cfg = new InMemoryDirectoryServerConfig("dc=valkeyry,dc=io");
        cfg.addAdditionalBindCredentials("cn=admin,dc=valkeyry,dc=io", "adminpw");
        cfg.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("test", 0)); // ephemeral port
        cfg.setSchema(null);
        ldap = new InMemoryDirectoryServer(cfg);

        ldap.add(new Entry("dn: dc=valkeyry,dc=io",
                "objectClass: top", "objectClass: domain", "dc: valkeyry"));
        ldap.add(new Entry("dn: ou=people,dc=valkeyry,dc=io",
                "objectClass: top", "objectClass: organizationalUnit", "ou: people"));
        // bob — one tenant (acme)
        ldap.add(new Entry("dn: uid=bob,ou=people,dc=valkeyry,dc=io",
                "objectClass: top", "objectClass: person", "objectClass: inetOrgPerson",
                "uid: bob", "cn: Bob Writer", "sn: Writer",
                "userPassword: secret",
                "ou: acme"));
        // alice — multi-tenant (acme + globex)
        ldap.add(new Entry("dn: uid=alice,ou=people,dc=valkeyry,dc=io",
                "objectClass: top", "objectClass: person", "objectClass: inetOrgPerson",
                "uid: alice", "cn: Alice Multi", "sn: Multi",
                "userPassword: s3cret",
                "ou: acme", "ou: globex"));

        ldap.startListening();

        int port = ldap.getListenPort("test");
        props = new LdapProperties();
        props.setUrl("ldap://127.0.0.1:" + port);
        props.setBaseDn("dc=valkeyry,dc=io");
        props.setUserDnPattern("uid={0},ou=people,dc=valkeyry,dc=io");
        props.setTenantAttribute("ou");
        props.setManagerDn("cn=admin,dc=valkeyry,dc=io");
        props.setManagerPassword("adminpw");
    }

    @AfterAll
    static void stopEmbeddedLdap() {
        if (ldap != null) ldap.shutDown(true);
    }

    @Test
    void singleTenantUserAuthenticatesWithWriterAuthority() {
        LdapBasicAuthenticationManager mgr = new LdapBasicAuthenticationManager(props);
        StepVerifier.create(mgr.authenticate(UsernamePasswordAuthenticationToken.unauthenticated("bob", "secret")))
                .assertNext(auth -> {
                    assertEquals("bob", auth.getName());
                    Set<String> roles = auth.getAuthorities().stream()
                            .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
                    assertTrue(roles.contains("ROLE_VALKEYRY_WRITER"), "LDAP agents are writers");
                    assertTrue(roles.contains("SCOPE_tenant:acme"), "ou=acme not mapped: " + roles);
                })
                .verifyComplete();
    }

    @Test
    void multiTenantUserGetsAllScopeTenantAuthorities() {
        LdapBasicAuthenticationManager mgr = new LdapBasicAuthenticationManager(props);
        StepVerifier.create(mgr.authenticate(UsernamePasswordAuthenticationToken.unauthenticated("alice", "s3cret")))
                .assertNext(auth -> {
                    Set<String> roles = auth.getAuthorities().stream()
                            .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
                    assertTrue(roles.contains("SCOPE_tenant:acme"),   "missing acme: " + roles);
                    assertTrue(roles.contains("SCOPE_tenant:globex"), "missing globex: " + roles);
                })
                .verifyComplete();
    }

    @Test
    void wrongPasswordIsRejectedAsBadCredentials() {
        LdapBasicAuthenticationManager mgr = new LdapBasicAuthenticationManager(props);
        StepVerifier.create(mgr.authenticate(UsernamePasswordAuthenticationToken.unauthenticated("bob", "wrong")))
                .expectError(BadCredentialsException.class)
                .verify();
    }

    @Test
    void unknownUserIsRejected() {
        LdapBasicAuthenticationManager mgr = new LdapBasicAuthenticationManager(props);
        StepVerifier.create(mgr.authenticate(UsernamePasswordAuthenticationToken.unauthenticated("ghost", "anything")))
                .expectError(BadCredentialsException.class)
                .verify();
    }
}
