package io.valkeyry.plugin.core;

import io.valkeyry.plugin.core.http.BatchEntryError;

import java.util.Collections;
import java.util.List;

/**
 * Summary of a {@code valkeyry-config push} run.
 *
 * <p>Back-compat: pre-v8 callers see {@code submitted / inserted / duplicates / tablesDeclared}
 * exactly as before. The new {@code failed} + {@code failures} fields capture the per-row
 * errors the server returned (anchored on the file each entry was loaded from), so build
 * logs can pinpoint exactly which JSON / data file needs fixing.</p>
 */
public record PluginResult(int submitted, int inserted, int duplicates, int failed,
                           List<String> tablesDeclared, List<BatchEntryError> failures) {
    public PluginResult {
        tablesDeclared = Collections.unmodifiableList(tablesDeclared);
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    /** Convenience ctor that mirrors the pre-v8 four-arg shape. */
    public PluginResult(int submitted, int inserted, int duplicates, List<String> tablesDeclared) {
        this(submitted, inserted, duplicates, 0, tablesDeclared, List.of());
    }

    /** True when at least one entry failed; signals to the build-tool that exit-code != 0. */
    public boolean hasFailures() { return failed > 0; }
}
