package core.com.rylinaux.plugman.file;

/*
 * #%L
 * PlugMan
 * %%
 * Copyright (C) 2010 - 2014 PlugMan
 * %%
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 * #L%
 */

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarFile;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Simple plugin descriptor that reads basic information from Bukkit and Paper plugin descriptors.
 *
 * @author rylinaux
 */
public record PluginDescriptor(String name) {

    private static final List<String> DESCRIPTOR_FILES = List.of("paper-plugin.yml", "plugin.yml");

    /**
     * Read the first supported descriptor from a plugin jar.
     * Paper's descriptor takes precedence when a jar contains both descriptor types.
     *
     * @param file plugin jar
     * @return the descriptor, or an empty optional when the jar has no supported descriptor
     * @throws IOException if the jar or descriptor cannot be read
     */
    public static Optional<PluginDescriptor> fromJar(File file) throws IOException {
        try (var jarFile = new JarFile(file)) {
            for (var descriptorFile : DESCRIPTOR_FILES) {
                var entry = jarFile.getJarEntry(descriptorFile);
                if (entry == null) continue;

                try (var stream = jarFile.getInputStream(entry)) {
                    return Optional.of(fromInputStream(stream));
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Create a plugin descriptor from an input stream containing plugin descriptor content.
     */
    public static PluginDescriptor fromInputStream(InputStream stream) throws IOException {
        try (stream) {
            var yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            var document = yaml.load(stream);
            if (!(document instanceof Map<?, ?> descriptor))
                throw new IOException("Plugin descriptor is not a YAML mapping");

            var nameValue = descriptor.get("name");
            if (nameValue == null) throw new IOException("No name field found in plugin descriptor");

            var name = nameValue.toString().trim();
            if (name.isEmpty()) throw new IOException("No name field found in plugin descriptor");
            return new PluginDescriptor(name);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid plugin descriptor", exception);
        }
    }
}
