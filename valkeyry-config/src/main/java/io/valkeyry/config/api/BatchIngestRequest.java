package io.valkeyry.config.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** Batch ingestion payload — used by the build-tool plugin. */
public record BatchIngestRequest(
        @NotEmpty @Valid List<IngestRecordRequest> entries
) {}
