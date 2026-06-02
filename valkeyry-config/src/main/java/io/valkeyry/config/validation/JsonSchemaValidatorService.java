package io.valkeyry.config.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.*;
import io.valkeyry.config.error.SchemaValidationException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Validates a payload against a JSON Schema (Draft 2020-12). Schemas with no {@code $schema}
 * declaration default to that draft.
 */
@Component
public class JsonSchemaValidatorService {

    private static final JsonSchemaFactory FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private final ObjectMapper mapper;

    public JsonSchemaValidatorService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Throws {@link SchemaValidationException} listing all violations. */
    public void validateOrThrow(JsonNode schemaNode, JsonNode payload) {
        SchemaValidatorsConfig cfg = new SchemaValidatorsConfig();
        cfg.setFailFast(false);
        JsonSchema schema = FACTORY.getSchema(schemaNode, cfg);
        Set<ValidationMessage> messages = schema.validate(payload);
        if (!messages.isEmpty()) {
            List<String> violations = messages.stream().map(ValidationMessage::getMessage).toList();
            throw new SchemaValidationException(violations);
        }
    }

    public ObjectMapper mapper() { return mapper; }
}
