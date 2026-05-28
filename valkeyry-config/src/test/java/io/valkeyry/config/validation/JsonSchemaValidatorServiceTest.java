package io.valkeyry.config.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.valkeyry.config.error.SchemaValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class JsonSchemaValidatorServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonSchemaValidatorService validator = new JsonSchemaValidatorService(mapper);

    @Test
    void validPayloadPassesThrough() throws Exception {
        var schema = mapper.readTree("""
                {
                  "$schema": "https://json-schema.org/draft/2020-12/schema",
                  "type": "object",
                  "required": ["id"],
                  "properties": { "id": { "type": "string" } }
                }""");
        var payload = mapper.readTree("{\"id\":\"abc\"}");
        assertDoesNotThrow(() -> validator.validateOrThrow(schema, payload));
    }

    @Test
    void missingRequiredFieldThrows() throws Exception {
        var schema = mapper.readTree("""
                {
                  "type": "object",
                  "required": ["id"],
                  "properties": { "id": { "type": "string" } }
                }""");
        var payload = mapper.readTree("{}");
        assertThatThrownBy(() -> validator.validateOrThrow(schema, payload))
                .isInstanceOf(SchemaValidationException.class);
    }
}
