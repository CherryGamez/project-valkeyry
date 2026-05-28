package io.valkeyry.plugin.gradle;

import io.valkeyry.plugin.core.PluginContext;
import io.valkeyry.plugin.core.PluginEngine;
import io.valkeyry.plugin.core.PluginLog;
import io.valkeyry.plugin.core.PluginResult;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.TaskAction;

import java.nio.file.Path;

public abstract class PushTask extends DefaultTask {

    @InputFile public abstract RegularFileProperty getManifestPath();
    @Input     public abstract Property<Boolean> getSkip();

    @TaskAction
    public void run() throws Exception {
        if (Boolean.TRUE.equals(getSkip().get())) {
            getLogger().lifecycle("[valkeyry-config] skipped (skip=true)");
            return;
        }
        Path manifest = getManifestPath().get().getAsFile().toPath();
        Path baseDir = getProject().getProjectDir().toPath();
        PluginLog log = new GradlePluginLog(getLogger());
        PluginResult res = PluginEngine.run(new PluginContext(manifest, baseDir, log));
        log.info(String.format("valkeyry-config push complete — submitted=%d inserted=%d duplicates=%d",
                res.submitted(), res.inserted(), res.duplicates()));
    }

    private static final class GradlePluginLog implements PluginLog {
        private final Logger logger;
        GradlePluginLog(Logger logger) { this.logger = logger; }
        @Override public void info(String msg) { logger.lifecycle(msg); }
        @Override public void warn(String msg) { logger.warn(msg); }
        @Override public void debug(String msg) { logger.debug(msg); }
        @Override public void error(String msg, Throwable t) { logger.error(msg, t); }
    }
}
