package io.valkeyry.config.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bind for {@code valkeyry.ldap.*} — Track 2 directory configuration. */
@ConfigurationProperties(prefix = "valkeyry.ldap")
public class LdapProperties {

    private String url = "ldap://localhost:1389";
    private String baseDn = "dc=valkeyry,dc=io";
    private String userDnPattern = "uid={0},ou=people,dc=valkeyry,dc=io";
    private String tenantAttribute = "ou";
    private String managerDn = "";
    private String managerPassword = "";

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getBaseDn() { return baseDn; }
    public void setBaseDn(String baseDn) { this.baseDn = baseDn; }
    public String getUserDnPattern() { return userDnPattern; }
    public void setUserDnPattern(String userDnPattern) { this.userDnPattern = userDnPattern; }
    public String getTenantAttribute() { return tenantAttribute; }
    public void setTenantAttribute(String tenantAttribute) { this.tenantAttribute = tenantAttribute; }
    public String getManagerDn() { return managerDn; }
    public void setManagerDn(String managerDn) { this.managerDn = managerDn; }
    public String getManagerPassword() { return managerPassword; }
    public void setManagerPassword(String managerPassword) { this.managerPassword = managerPassword; }
}
