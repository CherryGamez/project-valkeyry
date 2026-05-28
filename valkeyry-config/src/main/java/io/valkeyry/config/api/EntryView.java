package io.valkeyry.config.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** Outbound projection of a {@code VirtualTableEntry}. */
public record EntryView(
        UUID id,
        String tenantId,
        String tableName,
        String recordKey,
        long version,
        boolean latest,
        String payloadHash,
        JsonNode data,
        Instant createdAt,
        String createdBy
) {}
