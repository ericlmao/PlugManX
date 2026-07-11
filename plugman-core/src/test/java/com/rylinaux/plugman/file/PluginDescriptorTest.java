package com.rylinaux.plugman.file;

import core.com.rylinaux.plugman.file.PlugManFileManager;
import core.com.rylinaux.plugman.file.PluginDescriptor;
import core.com.rylinaux.plugman.logging.PluginLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginDescriptorTest {

    @TempDir
    Path tempDir;

    @Test
    void readsPaperPluginDescriptor() throws IOException {
        var pluginJar = createJar("paper.jar", Map.of(
                "paper-plugin.yml", "name: PaperOnly\nversion: 1.0\nmain: example.PaperPlugin\n"
        ));

        var descriptor = PluginDescriptor.fromJar(pluginJar.toFile());

        assertTrue(descriptor.isPresent());
        assertEquals("PaperOnly", descriptor.get().name());
    }

    @Test
    void keepsSupportingBukkitPluginDescriptor() throws IOException {
        var pluginJar = createJar("bukkit.jar", Map.of(
                "plugin.yml", "name: BukkitOnly\nversion: 1.0\nmain: example.BukkitPlugin\n"
        ));

        var descriptor = PluginDescriptor.fromJar(pluginJar.toFile());

        assertTrue(descriptor.isPresent());
        assertEquals("BukkitOnly", descriptor.get().name());
    }

    @Test
    void prefersPaperDescriptorWhenJarContainsBoth() throws IOException {
        var pluginJar = createJar("dual.jar", Map.of(
                "plugin.yml", "name: BukkitName\n",
                "paper-plugin.yml", "name: 'PaperName'\n"
        ));

        var descriptor = PluginDescriptor.fromJar(pluginJar.toFile());

        assertTrue(descriptor.isPresent());
        assertEquals("PaperName", descriptor.get().name());
    }

    @Test
    void parsesQuotedNameWithInlineComment() throws IOException {
        var pluginJar = createJar("quoted.jar", Map.of(
                "paper-plugin.yml", "name: \"QuotedPaper\" # the plugin's declared name\n"
        ));

        var descriptor = PluginDescriptor.fromJar(pluginJar.toFile());

        assertTrue(descriptor.isPresent());
        assertEquals("QuotedPaper", descriptor.get().name());
    }

    @Test
    void parsesYamlSpacingAroundNameKey() throws IOException {
        var pluginJar = createJar("spacing.jar", Map.of(
                "paper-plugin.yml", "name : 'SpacedPaper'\n"
        ));

        var descriptor = PluginDescriptor.fromJar(pluginJar.toFile());

        assertTrue(descriptor.isPresent());
        assertEquals("SpacedPaper", descriptor.get().name());
    }

    @Test
    void returnsEmptyWhenJarHasNoPluginDescriptor() throws IOException {
        var pluginJar = createJar("library.jar", Map.of("example.txt", "not a plugin"));

        assertTrue(PluginDescriptor.fromJar(pluginJar.toFile()).isEmpty());
    }

    @Test
    void fileManagerTracksPaperPluginNameForAutoFeatures() throws IOException {
        var pluginJar = createJar("renamed-file.jar", Map.of(
                "paper-plugin.yml", "name: DeclaredPaperName\nversion: 1.0\nmain: example.PaperPlugin\n"
        ));
        var fileManager = new PlugManFileManager(new NoOpPluginLogger());

        fileManager.trackFile(pluginJar.toFile());

        assertEquals("DeclaredPaperName", fileManager.getPluginNameForFile("renamed-file.jar"));
    }

    private Path createJar(String fileName, Map<String, String> entries) throws IOException {
        var jarPath = tempDir.resolve(fileName);
        try (var output = new JarOutputStream(java.nio.file.Files.newOutputStream(jarPath))) {
            for (var entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return jarPath;
    }

    private static final class NoOpPluginLogger implements PluginLogger {

        @Override
        public void info(String message) {
        }

        @Override
        public void info(String message, Throwable throwable) {
        }

        @Override
        public void warning(String message) {
        }

        @Override
        public void warning(String message, Throwable throwable) {
        }

        @Override
        public void severe(String message) {
        }

        @Override
        public void severe(String message, Throwable throwable) {
        }
    }
}
