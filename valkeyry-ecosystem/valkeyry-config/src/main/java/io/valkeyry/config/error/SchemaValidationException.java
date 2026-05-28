package io.valkeyry.config.error;

import java.util.List;

public class SchemaValidationException extends RuntimeException {
    private final List<String> violations;
    public SchemaValidationException(List<String> violations) {
        super("Schema validation failed: " + String.join("; ", violations));
        this.violations = List.copyOf(violations);
    }
    public List<String> violations() { return violations; }
}
