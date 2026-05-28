package io.valkeyry.config.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** Outbound projection of a {@code VirtualTableRegistry}. */
public record VirtualTableView(
        UUID id,
        String tenantId,
        String tableName,
        long configVersion,
        boolean active,
        JsonNode schema,
        Instant createdAt,
        String createdBy
) {}
