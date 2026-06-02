package io.valkeyry.config.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record DeclareVirtualTableRequest(
        @NotBlank String tableName,
        @NotNull  JsonNode schema
) {}
