package io.valkeyry.plugin.core.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManifestLoaderTest {

    @Test
    void loadsAndResolvesEnvPlaceholders(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("valkeyry-config.yaml");
        Files.writeString(file, """
                endpoint: ${MISSING_ENV:http://localhost:8081}
                tenant: demo
                auth:
                  type: basic
                  username: build-bot
                  password: ${PLUGIN_PASS:s3cret}
                tables:
                  - name: customers
                    schema: schemas/customers.schema.json
                    entries: data/customers/*.json
                """);
        PluginManifest m = ManifestLoader.load(file);
        assertThat(m.getEndpoint()).isEqualTo("http://localhost:8081");
        assertThat(m.getTenant()).isEqualTo("demo");
        assertThat(m.getAuth().getPassword()).isEqualTo("s3cret");
        assertThat(m.getTables()).hasSize(1);
    }

    @Test
    void rejectsMissingEndpoint(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("bad.yaml");
        Files.writeString(file, """
                tenant: demo
                tables:
                  - name: x
                    schema: s.json
                    entries: e/*.json
                """);
        assertThatThrownBy(() -> ManifestLoader.load(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endpoint");
    }

    @Test
    void loadsInlineSchema(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("valkeyry-config.yaml");
        Files.writeString(file, """
                endpoint: http://localhost:8081
                tenant: demo
                auth:
                  type: api-key
                  apiKey: plugin-test-key
                tables:
                  - name: feature_flags
                    schemaInline:
                      type: object
                      required: [key, enabled]
                      properties:
                        key:     { type: string }
                        enabled: { type: boolean }
                        audiences:
                          type: array
                          uniqueItems: true
                          items:
                            type: string
                            enum: [internal, beta, ga]
                    entries: data/flags/*.json
                """);
        PluginManifest m = ManifestLoader.load(file);
        assertThat(m.getTables()).hasSize(1);
        PluginManifest.TableSpec t = m.getTables().get(0);
        assertThat(t.hasInlineSchema()).isTrue();
        assertThat(t.getSchema()).isNull();
        assertThat(t.getSchemaInline()).containsKey("properties");
    }

    @Test
    void rejectsTableWithNeitherSchemaNorInline(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("bad.yaml");
        Files.writeString(file, """
                endpoint: http://localhost:8081
                tenant: demo
                tables:
                  - name: orphans
                    entries: data/orphans/*.json
                """);
        assertThatThrownBy(() -> ManifestLoader.load(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema");
    }

    @Test
    void rejectsTableWithBothSchemaAndInline(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("bad.yaml");
        Files.writeString(file, """
                endpoint: http://localhost:8081
                tenant: demo
                tables:
                  - name: dual
                    schema: schemas/dual.schema.json
                    schemaInline:
                      type: object
                """);
        assertThatThrownBy(() -> ManifestLoader.load(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("both");
    }
}
