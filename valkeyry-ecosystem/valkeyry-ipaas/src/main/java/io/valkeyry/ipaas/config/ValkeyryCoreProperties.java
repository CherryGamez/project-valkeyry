package io.valkeyry.ipaas.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Single annotation hub so each property class is registered exactly once. */
@Configuration
@EnableConfigurationProperties({
        BrokerProperties.class,
        ClaimCheckProperties.class,
        RateLimitProperties.class,
        LdapProperties.class
})
public class ValkeyryCoreProperties {}
