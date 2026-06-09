package io.valkeyry.config.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Outcome of a batch ingest call.
 *
 * <p>Back-compat note: the original shape ({@code submitted}, {@code inserted},
 * {@code duplicates}, {@code results}) is preserved. Two fields were added in v8 of the
 * bulk-import contract to give the caller a row-level breakdown of what went wrong without
 * having to scrape server logs:</p>
 *
 * <ul>
 *   <li>{@code failed} — number of entries the server could not persist for any reason
 *       other than idempotency-dedup. Equal to {@code errors.size()}.</li>
 *   <li>{@code errors} — one {@link BatchEntryError} per failed entry, anchored on the
 *       caller-supplied position via {@code index} (and the {@code recordKey} when known).</li>
 * </ul>
 *
 * <p>Invariant: {@code submitted == inserted + duplicates + failed}.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatchIngestResponse(
        int submitted,
        int inserted,
        int duplicates,
        int failed,
        List<EntryView> results,
        List<BatchEntryError> errors
) {
    /** Convenience factory mirroring the pre-v8 shape (no failed rows). */
    public static BatchIngestResponse allOk(int submitted, int inserted, int duplicates, List<EntryView> results) {
        return new BatchIngestResponse(submitted, inserted, duplicates, 0, results, List.of());
    }
}
