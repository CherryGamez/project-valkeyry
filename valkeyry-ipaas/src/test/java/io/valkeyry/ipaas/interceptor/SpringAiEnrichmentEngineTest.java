package io.valkeyry.ipaas.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.valkeyry.ipaas.config.IpaasProperties;
import io.valkeyry.ipaas.config.SpringAiConfig.ChatModelRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-unit test for the JSON-extraction logic of {@link SpringAiEnrichmentEngine}.
 * The ChatClient side is wired via {@link ChatModelRegistry}; we don't exercise the
 * real LLM call here — that's covered by integration tests with a stubbed ChatModel.
 */
class SpringAiEnrichmentEngineTest {

    private final SpringAiEnrichmentEngine engine = new SpringAiEnrichmentEngine(
            new ChatModelRegistry(Map.of()), new IpaasProperties(), new ObjectMapper());

    @Test
    void parsesStrictJson() {
        Map<String, Object> m = engine.parseJson("{\"a\":1,\"b\":\"two\"}");
        assertThat(m).containsEntry("a", 1).containsEntry("b", "two");
    }

    @Test
    void parsesMarkdownFencedJson() {
        String reply = "```json\n{\"decision\":\"primary\",\"confidence\":0.91}\n```";
        Map<String, Object> m = engine.parseJson(reply);
        assertThat(m).containsEntry("decision", "primary");
        assertThat(((Number) m.get("confidence")).doubleValue()).isEqualTo(0.91);
    }

    @Test
    void parsesProseWithEmbeddedJson() {
        String reply = "Sure! Here is the result:\n{\"x\":42}\nThanks.";
        Map<String, Object> m = engine.parseJson(reply);
        assertThat(m).containsEntry("x", 42);
    }

    @Test
    void returnsNullOnGarbage() {
        assertThat(engine.parseJson("not json at all")).isNull();
    }

    @Test
    void returnsNullOnNullInput() {
        assertThat(engine.parseJson(null)).isNull();
    }
}
