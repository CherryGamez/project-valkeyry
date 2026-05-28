package io.valkeyry.plugin.gradle;

import org.gradle.api.Project;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

public abstract class ValkeyryConfigExtension {

    private final Project project;

    public ValkeyryConfigExtension(Project project) {
        this.project = project;
        getManifestPath().convention(project.getLayout().getProjectDirectory().file("valkeyry-config.yaml"));
        getSkip().convention(false);
    }

    public abstract RegularFileProperty getManifestPath();
    public abstract Property<Boolean> getSkip();

    @SuppressWarnings("unused")
    Project project() { return project; }
}
