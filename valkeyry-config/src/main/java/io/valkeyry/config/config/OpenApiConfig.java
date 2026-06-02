package io.valkeyry.config.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the OpenAPI 3 document Swagger-UI renders at <code>/swagger-ui.html</code>.
 *
 * <p>Two security schemes are advertised so the "Authorize" dialog in Swagger-UI lets
 * devs paste either an API key or an OIDC bearer:</p>
 * <ul>
 *   <li><b>apiKey</b> — sent as <code>X-API-Key: &lt;key&gt;</code> (matches
 *       {@code ApiKeyAuthenticationConverter}).</li>
 *   <li><b>bearerAuth</b> — sent as <code>Authorization: Bearer &lt;jwt&gt;</code>
 *       (matches the OAuth2 resource-server chain).</li>
 * </ul>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI valkeyryConfigOpenApi() {
        SecurityScheme apiKey = new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .name("X-API-Key")
                .description("Static per-tenant key declared in VALKEYRY_API_KEYS=<key>:<tenant>");

        SecurityScheme bearer = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("OIDC access token whose 'tenants' claim contains the tenant id");

        return new OpenAPI()
                .info(new Info()
                        .title("Valkeyry Config — Headless Schema Registry")
                        .version("v1")
                        .description("""
                                Declare virtual tables (JSON-Schema), ingest dedup-by-hash records,
                                browse the append-only audit ledger, and fan out audit events to
                                signed webhooks. All endpoints are tenant-scoped under
                                `/api/v1/tenants/{tenantId}/…`.""")
                        .contact(new Contact().name("Valkeyry").url("https://github.com/valkeyry"))
                        .license(new License().name("Apache 2.0")))
                .components(new Components()
                        .addSecuritySchemes("apiKey", apiKey)
                        .addSecuritySchemes("bearerAuth", bearer))
                .addSecurityItem(new SecurityRequirement().addList("apiKey"))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
