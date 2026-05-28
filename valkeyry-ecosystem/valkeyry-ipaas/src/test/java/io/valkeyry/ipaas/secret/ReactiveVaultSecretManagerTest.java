package io.valkeyry.ipaas.secret;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.vault.core.ReactiveVaultTemplate;
import org.springframework.vault.support.VaultResponse;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReactiveVaultSecretManagerTest {

    @Mock ReactiveVaultTemplate vault;
    @InjectMocks ReactiveVaultSecretManager mgr;

    @Test
    void resolve_unwrapsKvV2DataEnvelope() {
        VaultResponse resp = new VaultResponse();
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("data", Map.of("access_key", "AKIA-test", "secret_key", "sekret"));
        envelope.put("metadata", Map.of("version", 1));
        resp.setData(envelope);
        when(vault.read("secret/data/tenant/project/storage")).thenReturn(Mono.just(resp));

        StepVerifier.create(mgr.resolve("secret/data/tenant/project/storage"))
                .assertNext(m -> {
                    org.assertj.core.api.Assertions.assertThat(m)
                            .containsEntry("access_key", "AKIA-test")
                            .containsEntry("secret_key", "sekret");
                })
                .verifyComplete();
    }

    @Test
    void resolve_emptyVaultEmitsError() {
        when(vault.read("missing")).thenReturn(Mono.empty());
        StepVerifier.create(mgr.resolve("missing"))
                .expectErrorMatches(e -> e instanceof IllegalStateException
                        && e.getMessage().contains("not found"))
                .verify();
    }
}
