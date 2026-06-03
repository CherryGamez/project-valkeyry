package io.valkeyry.config.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bind for {@code valkeyry.auth.*}.
 *
 * <p>Drives the runtime auth posture:
 * <ul>
 *   <li>{@code ssoEnabled} — wires the external OIDC resource-server JWT decoder.</li>
 *   <li>{@code ldapEnabled} — mounts the LDAP basic auth filter <em>and</em> the LDAP-backed form login path.</li>
 *   <li>{@code adminUsername}/{@code adminPassword} — a built-in local admin that always works
 *       regardless of SSO/LDAP state (so devs can run the app stand-alone).</li>
 *   <li>{@code jwtSecret} — HS256 symmetric key used to mint/verify local-issued JWTs (form login
 *       and local-admin bypass). Must be at least 32 bytes in production; defaults to a fixed dev
 *       value so single-user local runs work out of the box.</li>
 *   <li>{@code jwtTtlSeconds} — token lifetime.</li>
 *   <li>{@code jwtIssuer} — {@code iss} claim emitted by the local mint; also used by the composite
 *       JWT decoder to route incoming tokens to the right verifier.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "valkeyry.auth")
public class AuthProperties {

    private boolean ssoEnabled = false;
    private boolean ldapEnabled = false;
    private String adminUsername = "admin";
    private String adminPassword = "admin";
    /** ≥ 32 bytes for HS256. Dev default — overridden via VALKEYRY_JWT_SECRET in any real deploy. */
    private String jwtSecret = "valkeyry-dev-secret-please-override-in-production-32b!!";
    private long jwtTtlSeconds = 8 * 60 * 60L; // 8h
    private String jwtIssuer = "valkeyry-local";

    public boolean isSsoEnabled() { return ssoEnabled; }
    public void setSsoEnabled(boolean ssoEnabled) { this.ssoEnabled = ssoEnabled; }
    public boolean isLdapEnabled() { return ldapEnabled; }
    public void setLdapEnabled(boolean ldapEnabled) { this.ldapEnabled = ldapEnabled; }
    public String getAdminUsername() { return adminUsername; }
    public void setAdminUsername(String adminUsername) { this.adminUsername = adminUsername; }
    public String getAdminPassword() { return adminPassword; }
    public void setAdminPassword(String adminPassword) { this.adminPassword = adminPassword; }
    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
    public long getJwtTtlSeconds() { return jwtTtlSeconds; }
    public void setJwtTtlSeconds(long jwtTtlSeconds) { this.jwtTtlSeconds = jwtTtlSeconds; }
    public String getJwtIssuer() { return jwtIssuer; }
    public void setJwtIssuer(String jwtIssuer) { this.jwtIssuer = jwtIssuer; }
}
