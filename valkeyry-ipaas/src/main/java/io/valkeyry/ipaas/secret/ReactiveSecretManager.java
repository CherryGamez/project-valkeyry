package io.valkeyry.ipaas.secret;

import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Reactive abstraction over HashiCorp Vault KV-v2 lookups. Returns the raw
 * data map at the supplied secret path. Never caches secrets in-memory.
 */
public interface ReactiveSecretManager {
    Mono<Map<String, Object>> resolve(String vaultSecretPath);
}
