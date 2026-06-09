package io.valkeyry.config.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Per-row error report inside a {@link BatchIngestResponse}.
 *
 * <p>Emitted for every entry that failed (i.e. neither inserted nor a duplicate). It carries
 * enough context for the caller to point at the offending row in their source spreadsheet
 * / JSON file without having to inspect server logs.</p>
 *
 * <p>Field reference:</p>
 * <ul>
 *   <li>{@code index} — 0-based position of the entry inside the original
 *       {@code BatchIngestRequest.entries[]}. This is the canonical anchor: if the caller
 *       is a build-tool plugin that read row N from {@code customers/customer-3.json} into
 *       the N-th entry, then {@code index == N} maps the failure back to that file.</li>
 *   <li>{@code recordKey} — value as submitted; may be {@code null} if the row was rejected
 *       before bean-validation completed (e.g. malformed JSON).</li>
 *   <li>{@code errorType} — short stable token (e.g. {@code schema-violation},
 *       {@code table-not-found}, {@code invalid-record-key}, {@code internal}). Suitable
 *       for programmatic branching in CI scripts.</li>
 *   <li>{@code httpStatus} — what the equivalent single-entry ingest would have returned
 *       (422 for schema, 404 for missing table, 400 for malformed payload, 500 fallback).</li>
 *   <li>{@code message} — human-readable detail.</li>
 *   <li>{@code violations} — populated for {@code schema-violation}; each item is one
 *       JSON-Schema rule failure (e.g. {@code $.age: must be ≥ 0}).</li>
 *   <li>{@code traceId} — present for {@code errorType=internal}; matches the {@code traceId}
 *       MDC field in server logs so a developer can {@code grep} for the exact stack trace.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record BatchEntryError(
        int index,
        String recordKey,
        String status,
        String errorType,
        int httpStatus,
        String message,
        List<String> violations,
        String traceId
) {
    /** Convenience builder for schema violations. */
    public static BatchEntryError schemaViolation(int index, String recordKey,
                                                  String message, List<String> violations) {
        return new BatchEntryError(index, recordKey, "failed", "schema-violation", 422,
                message, violations == null ? List.of() : List.copyOf(violations), null);
    }

    public static BatchEntryError tableNotFound(int index, String recordKey, String message) {
        return new BatchEntryError(index, recordKey, "failed", "table-not-found", 404,
                message, List.of(), null);
    }

    public static BatchEntryError invalidRecordKey(int index, String recordKey, String message) {
        return new BatchEntryError(index, recordKey, "failed", "invalid-record-key", 400,
                message, List.of(), null);
    }

    public static BatchEntryError invalidData(int index, String recordKey, String message) {
        return new BatchEntryError(index, recordKey, "failed", "invalid-data", 400,
                message, List.of(), null);
    }

    public static BatchEntryError internal(int index, String recordKey, String message, String traceId) {
        return new BatchEntryError(index, recordKey, "failed", "internal", 500,
                message, List.of(), traceId);
    }
}
