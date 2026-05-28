package io.valkeyry.ipaas.secret;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.vault.core.ReactiveVaultTemplate;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReactiveVaultSecretManager implements ReactiveSecretManager {

    private final ReactiveVaultTemplate vault;

    @Override
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> resolve(String vaultSecretPath) {
        return vault.read(vaultSecretPath)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "Vault secret not found at path: " + vaultSecretPath)))
                .map(resp -> {
                    Object raw = resp.getData();
                    if (raw == null) {
                        throw new IllegalStateException("Vault returned empty data at " + vaultSecretPath);
                    }
                    // KV-v2 wraps actual key/values under "data".
                    if (raw instanceof Map<?, ?> outer && outer.get("data") instanceof Map<?, ?> inner) {
                        return (Map<String, Object>) inner;
                    }
                    return (Map<String, Object>) raw;
                });
    }
}
