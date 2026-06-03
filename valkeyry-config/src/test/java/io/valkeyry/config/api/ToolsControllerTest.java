package io.valkeyry.config.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit-level tests for the file → JSON converters. We bypass Spring's multipart wiring by
 * implementing a minimal {@link FilePart} test double — the controller only consumes the
 * filename + a {@code Flux<DataBuffer>} so this is enough to exercise the parsing branches.
 */
class ToolsControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolsController tools = new ToolsController(mapper);

    @Test
    void csvConvertsHeaderAndRows() {
        String csv = "id,email,active\nu1,jane@acme.io,true\nu2,bob@acme.io,false\n";
        FakeFilePart part = new FakeFilePart("users.csv", csv.getBytes(StandardCharsets.UTF_8));
        StepVerifier.create(tools.convertCsv(part))
                .assertNext(json -> {
                    assertEquals("csv", json.get("source").asText());
                    JsonNode table = json.get("tables").get(0);
                    assertEquals("users", table.get("tableName").asText());
                    assertEquals(3, table.get("columns").size());
                    assertEquals(2, table.get("rows").size());
                    assertEquals("jane@acme.io", table.get("rows").get(0).get("email").asText());
                })
                .verifyComplete();
    }

    @Test
    void dmnConvertsDecisionTable() {
        String dmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="defs" name="defs"
                             namespace="urn:test">
                  <decision id="grade" name="Grade">
                    <decisionTable hitPolicy="UNIQUE">
                      <input id="i1" label="score">
                        <inputExpression typeRef="integer"><text>score</text></inputExpression>
                      </input>
                      <output id="o1" name="grade" label="grade" typeRef="string"/>
                      <rule id="r1">
                        <inputEntry id="ie1"><text>&gt;= 80</text></inputEntry>
                        <outputEntry id="oe1"><text>"A"</text></outputEntry>
                      </rule>
                      <rule id="r2">
                        <inputEntry id="ie2"><text>&lt; 80</text></inputEntry>
                        <outputEntry id="oe2"><text>"B"</text></outputEntry>
                      </rule>
                    </decisionTable>
                  </decision>
                </definitions>
                """;
        FakeFilePart part = new FakeFilePart("grade.dmn", dmn.getBytes(StandardCharsets.UTF_8));
        StepVerifier.create(tools.convertDmn(part))
                .assertNext(json -> {
                    assertEquals("dmn", json.get("source").asText());
                    JsonNode table = json.get("tables").get(0);
                    assertEquals("Grade", table.get("tableName").asText());
                    assertEquals(2, table.get("rows").size());
                })
                .verifyComplete();
    }

    // ---- Minimal FilePart double ----
    static final class FakeFilePart implements FilePart {
        private final String name;
        private final byte[] bytes;
        FakeFilePart(String name, byte[] bytes) { this.name = name; this.bytes = bytes; }
        @Override public String filename() { return name; }
        @Override public Mono<Void> transferTo(Path dest) {
            try { Files.write(dest, bytes); } catch (Exception e) { return Mono.error(e); }
            return Mono.empty();
        }
        @Override public String name() { return "file"; }
        @Override public HttpHeaders headers() { return new HttpHeaders(); }
        @Override public Flux<DataBuffer> content() {
            DataBuffer buf = new DefaultDataBufferFactory().wrap(bytes);
            return Flux.just(buf);
        }
    }
}
