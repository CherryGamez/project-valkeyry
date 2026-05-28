package io.valkeyry.ipaas.claimcheck;

import io.valkeyry.ipaas.config.ClaimCheckProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the chosen {@link ClaimCheckStore} implementation. Today only S3/MinIO. */
@Configuration
public class ClaimCheckConfig {

    @Bean
    @ConditionalOnProperty(prefix = "valkeyry.claimcheck", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ClaimCheckStore s3ClaimCheckStore(ClaimCheckProperties props) {
        return new S3ClaimCheckStore(props);
    }
}
