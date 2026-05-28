package io.valkeyry.ipaas.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.vault.authentication.VaultTokenSupplier;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.ReactiveVaultTemplate;
import org.springframework.vault.support.VaultToken;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Map;

@Slf4j
@Configuration
public class VaultConfig {

    @Bean
    public ReactiveVaultTemplate reactiveVaultTemplate(
            @Value("${spring.cloud.vault.uri:http://localhost:8200}") String uri,
            @Value("${spring.cloud.vault.token:dev-root-token}") String token) {
        VaultEndpoint endpoint = VaultEndpoint.from(URI.create(uri));
        VaultTokenSupplier supplier = () -> Mono.just(VaultToken.of(token));
        return new ReactiveVaultTemplate(endpoint, new ReactorClientHttpConnector(), supplier);
    }

    /**
     * Periodically calls {@code POST /v1/auth/token/renew-self} so long-running
     * deployments keep their Vault session alive without ever blocking on a
     * synchronous renewal. Failures are logged at WARN — dev root tokens
     * (and non-renewable tokens) yield a 400 which we treat as a no-op.
     */
    @Component
    public static class VaultTokenRenewer {

        private final IpaasProperties props;
        private final String vaultUri;
        private final String token;
        private final WebClient webClient;

        public VaultTokenRenewer(IpaasProperties props,
                                 @Value("${spring.cloud.vault.uri:http://localhost:8200}") String vaultUri,
                                 @Value("${spring.cloud.vault.token:dev-root-token}") String token) {
            this.props = props;
            this.vaultUri = vaultUri;
            this.token = token;
            this.webClient = WebClient.builder().baseUrl(vaultUri).build();
        }

        @Scheduled(fixedDelayString = "${ipaas.vault.renewal-interval-ms:1800000}",
                   initialDelayString = "${ipaas.vault.renewal-interval-ms:1800000}")
        public void renew() {
            if (!props.getVault().isRenewalEnabled()) return;
            webClient.post()
                    .uri("/v1/auth/token/renew-self")
                    .header("X-Vault-Token", token)
                    .bodyValue(Map.of("increment", props.getVault().getRenewalIncrementSeconds() + "s"))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .doOnNext(r -> log.info("Vault token renewed; lease_duration={}", extractLease(r)))
                    .onErrorResume(ex -> {
                        // Root tokens / dev tokens are non-renewable → Vault responds 400.
                        log.debug("Vault renew-self skipped (non-renewable or unreachable): {}", ex.toString());
                        return Mono.empty();
                    })
                    .subscribe();
        }

        @SuppressWarnings("unchecked")
        private Object extractLease(Map<String, Object> resp) {
            if (resp == null) return "n/a";
            Object auth = resp.get("auth");
            if (auth instanceof Map<?, ?> a) return ((Map<String, Object>) a).get("lease_duration");
            return resp.get("lease_duration");
        }
    }
}
