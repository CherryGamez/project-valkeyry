package io.valkeyry.config.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** Outbound projection of {@link io.valkeyry.config.domain.ConfigAuditEntry}. */
public record AuditView(
        UUID id,
        String tenantId,
        String tableName,
        String operation,
        String recordKey,
        JsonNode beforeValue,
        JsonNode afterValue,
        String actor,
        String actorTrack,
        Instant changedAt,
        String requestId
) {}
