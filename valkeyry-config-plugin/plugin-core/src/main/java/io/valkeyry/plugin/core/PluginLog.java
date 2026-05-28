package io.valkeyry.plugin.core;

/** SLF4J-free abstraction so we can adapt to either Maven {@code Log} or Gradle {@code Logger}. */
public interface PluginLog {
    void info(String msg);
    void warn(String msg);
    void error(String msg, Throwable t);
    default void debug(String msg) {}
}
