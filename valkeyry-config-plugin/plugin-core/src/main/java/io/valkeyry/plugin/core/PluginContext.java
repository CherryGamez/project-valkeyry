package io.valkeyry.plugin.core;

import java.nio.file.Path;

/** Inputs handed to {@link PluginEngine#run(PluginContext)}. */
public record PluginContext(
        Path manifestPath,
        Path projectBaseDir,
        PluginLog log
) {}
