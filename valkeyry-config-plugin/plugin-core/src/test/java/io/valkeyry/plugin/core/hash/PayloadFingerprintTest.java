package io.valkeyry.plugin.core.hash;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadFingerprintTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void identicalContentDifferentKeyOrderProducesSameHash() throws Exception {
        String a = "{\"a\":1,\"b\":{\"x\":true,\"y\":[1,2,3]}}";
        String b = "{\"b\":{\"y\":[1,2,3],\"x\":true},\"a\":1}";
        assertThat(PayloadFingerprint.sha256(mapper.readTree(a)))
                .isEqualTo(PayloadFingerprint.sha256(mapper.readTree(b)));
    }

    @Test
    void differentArrayOrderProducesDifferentHash() throws Exception {
        String a = "{\"x\":[1,2,3]}";
        String b = "{\"x\":[3,2,1]}";
        assertThat(PayloadFingerprint.sha256(mapper.readTree(a)))
                .isNotEqualTo(PayloadFingerprint.sha256(mapper.readTree(b)));
    }

    @Test
    void hashIsLowercaseHex64Chars() throws Exception {
        String hash = PayloadFingerprint.sha256(mapper.readTree("{\"a\":1}"));
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    }
}
