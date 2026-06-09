package io.valkeyry.plugin.core.http;

import java.util.List;

/**
 * Per-row failure surfaced by the {@code POST /tables/{name}/entries:batch} endpoint.
 *
 * <p>Mirrors the server's {@code BatchEntryError} record (see {@code valkeyry-config}).
 * Anchored on the 0-based {@code index} the plugin used when it submitted the batch —
 * combine with {@link ValkeyryConfigClient.EntryRequest#sourceFile()} on the request side
 * to translate this back into "the JSON file the user typed".</p>
 */
public record BatchEntryError(
        int index,
        String recordKey,
        String errorType,
        int httpStatus,
        String message,
        List<String> violations,
        String traceId,
        String sourceFile
) {
    public BatchEntryError {
        violations = violations == null ? List.of() : List.copyOf(violations);
    }

    /** Concise developer-facing line used by the plugin's failure summary. */
    public String formatOneLine() {
        StringBuilder sb = new StringBuilder();
        if (sourceFile != null && !sourceFile.isBlank()) {
            sb.append(sourceFile).append(": ");
        } else {
            sb.append("row[").append(index).append("]: ");
        }
        if (recordKey != null) sb.append("recordKey=").append(recordKey).append(" — ");
        sb.append(errorType).append(" (HTTP ").append(httpStatus).append("): ").append(message);
        if (!violations.isEmpty()) {
            sb.append("\n        violations:");
            for (String v : violations) sb.append("\n          - ").append(v);
        }
        if (traceId != null && !traceId.isBlank()) {
            sb.append("\n        server log: grep traceId=").append(traceId);
        }
        return sb.toString();
    }
}
