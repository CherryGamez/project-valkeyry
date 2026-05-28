package io.valkeyry.plugin.maven;

import io.valkeyry.plugin.core.PluginLog;
import org.apache.maven.plugin.logging.Log;

final class MavenPluginLog implements PluginLog {
    private final Log delegate;
    MavenPluginLog(Log delegate) { this.delegate = delegate; }
    @Override public void info(String msg) { delegate.info(msg); }
    @Override public void warn(String msg) { delegate.warn(msg); }
    @Override public void debug(String msg) { delegate.debug(msg); }
    @Override public void error(String msg, Throwable t) { delegate.error(msg, t); }
}
