package io.valkeyry.config.validation;

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
    void serverAndPluginHashesAgree() throws Exception {
        // Same algorithm in plugin-core: must be byte-identical.
        String json = "{\"id\":\"alice\",\"tier\":\"gold\",\"flags\":[true,false]}";
        String server = PayloadFingerprint.sha256(mapper.readTree(json));
        String plugin = io.valkeyry.plugin.core.hash.PayloadFingerprint.sha256(mapper.readTree(json));
        assertThat(server).isEqualTo(plugin);
    }
}
