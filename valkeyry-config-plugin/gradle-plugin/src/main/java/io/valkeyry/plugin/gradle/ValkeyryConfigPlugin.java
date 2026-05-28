package io.valkeyry.plugin.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

/**
 * Gradle plugin entry point.
 *
 * <p>Apply with:</p>
 * <pre>{@code
 * plugins {
 *   id 'io.valkeyry.config' version '1.0.0-SNAPSHOT'
 * }
 *
 * valkeyryConfig {
 *   manifestPath = file('valkeyry-config.yaml')
 * }
 * }</pre>
 *
 * <p>Then run {@code ./gradlew valkeyryConfigPush}.</p>
 */
public class ValkeyryConfigPlugin implements Plugin<Project> {

    public static final String EXTENSION = "valkeyryConfig";
    public static final String TASK = "valkeyryConfigPush";

    @Override
    public void apply(Project project) {
        ValkeyryConfigExtension ext = project.getExtensions().create(EXTENSION, ValkeyryConfigExtension.class, project);
        project.getTasks().register(TASK, PushTask.class, task -> {
            task.setGroup("valkeyry");
            task.setDescription("Push virtual-table schemas + entries to valkeyry-config");
            task.getManifestPath().convention(ext.getManifestPath());
            task.getSkip().convention(ext.getSkip());
        });
    }
}
