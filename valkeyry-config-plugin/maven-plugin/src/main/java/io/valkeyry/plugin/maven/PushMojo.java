package io.valkeyry.plugin.maven;

import io.valkeyry.plugin.core.PluginContext;
import io.valkeyry.plugin.core.PluginEngine;
import io.valkeyry.plugin.core.PluginLog;
import io.valkeyry.plugin.core.PluginResult;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Maven goal: {@code valkeyry-config:push}.
 *
 * <p>Bind to the {@code deploy} lifecycle so the schema-registry update happens after
 * regular artifact deploy — failure here aborts deploy but does not corrupt artifacts.</p>
 *
 * <p>Wire example:</p>
 * <pre>{@code
 * <plugin>
 *   <groupId>io.valkeyry</groupId>
 *   <artifactId>valkeyry-config-maven-plugin</artifactId>
 *   <version>1.0.0-SNAPSHOT</version>
 *   <executions>
 *     <execution>
 *       <goals><goal>push</goal></goals>
 *     </execution>
 *   </executions>
 * </plugin>
 * }</pre>
 */
@Mojo(name = "push", defaultPhase = LifecyclePhase.DEPLOY, threadSafe = true)
public class PushMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** Manifest path relative to {@code ${project.basedir}}. */
    @Parameter(property = "valkeyry.manifest", defaultValue = "valkeyry-config.yaml")
    private String manifest;

    @Parameter(property = "valkeyry.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("[valkeyry-config] skipped (valkeyry.skip=true)");
            return;
        }
        Path baseDir = project.getBasedir().toPath();
        Path manifestPath = baseDir.resolve(manifest);
        PluginLog log = new MavenPluginLog(getLog());

        PluginContext ctx = new PluginContext(manifestPath, baseDir, log);
        try {
            PluginResult res = PluginEngine.run(ctx);
            log.info(String.format("valkeyry-config push complete — submitted=%d inserted=%d duplicates=%d",
                    res.submitted(), res.inserted(), res.duplicates()));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Interrupted", ie);
        } catch (Exception ex) {
            throw new MojoFailureException("valkeyry-config push failed: " + ex.getMessage(), ex);
        }
    }

    // Setters for tests
    void setProject(MavenProject project) { this.project = project; }
    void setManifest(String manifest) { this.manifest = manifest; }
    void setSkip(boolean skip) { this.skip = skip; }
}
