package io.valkeyry.plugin.core.manifest;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses {@code valkeyry-config.yaml} and resolves {@code ${ENV_VAR}} placeholders. */
public final class ManifestLoader {

    private static final Pattern ENV = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?\\}");

    private ManifestLoader() {}

    public static PluginManifest load(Path file) throws IOException {
        String yaml = Files.readString(file);
        String resolved = resolveEnv(yaml);
        Yaml parser = new Yaml(new Constructor(PluginManifest.class, new LoaderOptions()));
        PluginManifest manifest = parser.load(resolved);
        if (manifest == null) {
            throw new IllegalArgumentException("Empty manifest: " + file);
        }
        validate(manifest, file);
        return manifest;
    }

    private static String resolveEnv(String input) {
        Matcher m = ENV.matcher(input);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String var = m.group(1);
            String def = m.group(2) == null ? "" : m.group(2);
            String val = System.getenv(var);
            if (val == null || val.isEmpty()) val = def;
            m.appendReplacement(out, Matcher.quoteReplacement(val));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static void validate(PluginManifest m, Path file) {
        if (m.getEndpoint() == null || m.getEndpoint().isBlank()) {
            throw new IllegalArgumentException("Missing 'endpoint' in " + file);
        }
        if (m.getTenant() == null || m.getTenant().isBlank()) {
            throw new IllegalArgumentException("Missing 'tenant' in " + file);
        }
        if (m.getTables() == null || m.getTables().isEmpty()) {
            throw new IllegalArgumentException("Missing 'tables' in " + file);
        }
    }
}
