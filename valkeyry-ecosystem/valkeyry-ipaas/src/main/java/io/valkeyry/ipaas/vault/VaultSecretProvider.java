package io.valkeyry.ipaas.vault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultResponse;

import java.util.Map;
import java.util.Optional;

/**
 * Thin facade over Spring Vault for opaque secrets (broker credentials, S3 keys, …).
 *
 * <p>The bean is created lazily and tolerates an unavailable Vault — callers receive
 * {@link Optional#empty()} and can fall back to environment variables. This makes the engine
 * boot cleanly in environments without Vault (e.g. local dev, Testcontainers).</p>
 */
@Component
public class VaultSecretProvider {

    private static final Logger log = LoggerFactory.getLogger(VaultSecretProvider.class);

    private final VaultTemplate vault;

    public VaultSecretProvider(@Value("${spring.cloud.vault.uri:}") String uri,
                               @Value("${spring.cloud.vault.token:}") String token) {
        VaultTemplate v = null;
        try {
            if (!uri.isBlank() && !token.isBlank()) {
                org.springframework.vault.client.VaultEndpoint endpoint = org.springframework.vault.client.VaultEndpoint.from(java.net.URI.create(uri));
                v = new VaultTemplate(endpoint,
                        new org.springframework.vault.authentication.TokenAuthentication(token));
                log.info("Vault wired @ {}", uri);
            }
        } catch (Exception e) {
            log.warn("Vault not configured ({}); falling back to env-vars.", e.getMessage());
        }
        this.vault = v;
    }

    public Optional<Map<String, Object>> read(String path) {
        if (vault == null) return Optional.empty();
        try {
            VaultResponse resp = vault.read(path);
            return resp == null ? Optional.empty() : Optional.ofNullable(resp.getData());
        } catch (Exception ex) {
            log.warn("Vault read {} failed: {}", path, ex.getMessage());
            return Optional.empty();
        }
    }

    public Optional<String> readString(String path, String key) {
        return read(path).map(m -> stringify(m.get(key)));
    }

    private static String stringify(Object o) { return o == null ? null : o.toString(); }
}
