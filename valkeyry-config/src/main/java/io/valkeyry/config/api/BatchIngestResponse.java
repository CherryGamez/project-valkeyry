package io.valkeyry.config.api;

import java.util.List;

public record BatchIngestResponse(
        int submitted,
        int inserted,
        int duplicates,
        List<EntryView> results
) {}
