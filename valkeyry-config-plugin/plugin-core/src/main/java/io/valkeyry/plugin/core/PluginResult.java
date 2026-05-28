package io.valkeyry.plugin.core;

import java.util.Collections;
import java.util.List;

public record PluginResult(int submitted, int inserted, int duplicates, List<String> tablesDeclared) {
    public PluginResult { tablesDeclared = Collections.unmodifiableList(tablesDeclared); }
}
