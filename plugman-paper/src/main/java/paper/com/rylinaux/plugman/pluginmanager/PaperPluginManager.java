package paper.com.rylinaux.plugman.pluginmanager;

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

import bukkit.com.rylinaux.plugman.PlugManBukkit;
import bukkit.com.rylinaux.plugman.plugin.BukkitPlugin;
import bukkit.com.rylinaux.plugman.pluginmanager.BukkitPluginManager;
import core.com.rylinaux.plugman.PluginResult;
import core.com.rylinaux.plugman.plugins.Plugin;
import core.com.rylinaux.plugman.util.reflection.ClassAccessor;
import core.com.rylinaux.plugman.util.reflection.FieldAccessor;
import core.com.rylinaux.plugman.util.reflection.MethodAccessor;
import core.com.rylinaux.plugman.util.tuples.Tuple;
import io.papermc.paper.plugin.configuration.PluginMeta;
import org.bukkit.command.Command;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.logging.Level;

/**
 * Utilities for managing paper plugins.
 *
 * @author rylinaux
 */
public class PaperPluginManager extends BukkitPluginManager {

    private final LinkedHashSet<String> disabledPlugins = new LinkedHashSet<>();
    private final Map<String, Integer> pluginLoadPositions = new HashMap<>();
    private final List<String> disabledPluginEnableOrder = new ArrayList<>();

    public PaperPluginManager() {
        try {
            var pluginClassLoader = ClassAccessor.getClass("org.bukkit.plugin.java.PluginClassLoader");
            if (pluginClassLoader == null) throw new ClassNotFoundException("PluginClassLoader not found");
            var pluginClassLoaderPlugin = FieldAccessor.getField(pluginClassLoader, "plugin");
            if (pluginClassLoaderPlugin == null) throw new NoSuchFieldException("plugin field not found");
        } catch (ClassNotFoundException | NoSuchFieldException exception) {
            throw new RuntimeException(exception);
        }

        PaperCommandRegistrationTracker.captureBaseline();
    }

    public boolean isPaperPlugin(File file) {
        if (file == null) return false;

        JarFile jar = null;

        try {
            jar = new JarFile(file);
            var entry = jar.getJarEntry("paper-plugin.yml");

            return entry != null;
        } catch (IOException | YAMLException ex) {
            return false;
        } finally {
            if (jar != null) try {
                jar.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public boolean isPaperPlugin(Plugin plugin) {
        try {
            var launchEntryPointHandlerClass = ClassAccessor.getClass("io.papermc.paper.plugin.entrypoint.LaunchEntryPointHandler");
            if (launchEntryPointHandlerClass == null) return false;

            var instance = FieldAccessor.getValue(launchEntryPointHandlerClass, "INSTANCE", null);

            var getMethod = MethodAccessor.findMethodByName(instance.getClass(), "get");

            if (getMethod == null) return false;

            var entrypointClass = ClassAccessor.getClass("io.papermc.paper.plugin.entrypoint.Entrypoint");
            if (entrypointClass == null) return false;

            var pluginFieldValue = FieldAccessor.getValue(entrypointClass, "PLUGIN", null);

            var providerStorage = getMethod.invoke(instance, pluginFieldValue);

            if (providerStorage == null) return false;

            var providers = MethodAccessor.<List<?>>invoke(ClassAccessor.getClass("io.papermc.paper.plugin.storage.SimpleProviderStorage"),
                    "getRegisteredProviders", providerStorage);

            for (var provider : providers)
                try {
                    var meta = MethodAccessor.<PluginMeta>invoke(provider.getClass(), "getMeta", provider);
                    if (!meta.getName().equalsIgnoreCase(plugin.getName())) continue;

                    return ClassAccessor.assignableFrom("io.papermc.paper.plugin.provider.type.paper.PaperPluginParent$PaperServerPluginProvider", provider.getClass());
                } catch (Throwable ignored) {
                    return false;
                }

        } catch (Throwable throwable) {
            PlugManBukkit.getInstance().getLogger().log(Level.SEVERE, "Failed to check if plugin is a Paper plugin", throwable);
        }

        return false;
    }

    public boolean isFolia() {
        return ClassAccessor.classExists("io.papermc.paper.threadedregions.RegionizedServer");
    }

    @Override
    public boolean supportsDependencyAwareBulkReload() {
        return true;
    }

    /**
     * Paper closes and unregisters configured plugin class loaders when disabling them. Treat an
     * enable as a fresh load so the plugin receives a usable class loader and lifecycle handlers.
     */
    @Override
    public PluginResult enable(Plugin plugin) {
        if (plugin == null) return new PluginResult(false, "error.invalid-plugin");
        if (plugin.isEnabled()) return new PluginResult(false, "enable.already-enabled");

        var pluginName = plugin.getName();
        var trackedAsUnloaded = getDisabledPluginName(pluginName) != null;

        if (!trackedAsUnloaded) {
            var unloadResult = unload(plugin);
            if (!unloadResult.success()) return unloadResult;
        }

        var loadResult = load(pluginName);
        if (!loadResult.success()) return loadResult;
        return new PluginResult(true, "enable.enabled");
    }

    @Override
    public PluginResult enable(String name) {
        var target = getPluginByName(name);
        if (target != null) return enable(target);

        var disabledPluginName = getDisabledPluginName(name);
        if (disabledPluginName == null) return new PluginResult(false, "error.invalid-plugin");
        if (isIgnored(disabledPluginName)) return new PluginResult(false, "error.ignored");

        var loadResult = load(disabledPluginName);
        if (!loadResult.success()) return loadResult;
        return new PluginResult(true, "enable.enabled");
    }

    /**
     * A safe disable on Paper is a full unload. The name is retained so enable/restart can perform
     * a clean load rather than trying to reuse Paper's closed class loader.
     */
    @Override
    public PluginResult disable(Plugin plugin) {
        if (plugin == null) return new PluginResult(false, "plugin.null");
        if (!plugin.isEnabled()) return new PluginResult(false, "plugin.already-disabled");
        var pluginName = plugin.getName();
        captureDisabledPluginEnableOrder(false);
        var unloadResult = unload(plugin);
        if (!unloadResult.success()) return unloadResult;

        disabledPlugins.add(pluginName);
        return new PluginResult(true, "plugin.disabled");
    }

    @Override
    public PluginResult disableAll() {
        captureDisabledPluginEnableOrder(true);
        var pluginLoadOrder = getPlugins().stream()
                .filter(plugin -> !isIgnored(plugin))
                .map(Plugin::getName)
                .toList();
        var result = super.disableAll();

        // disableAll tears down in reverse order; retain names in forward load order.
        var orderedDisabledPlugins = new LinkedHashSet<String>();
        pluginLoadOrder.stream()
                .filter(pluginName -> getDisabledPluginName(pluginName) != null)
                .forEach(orderedDisabledPlugins::add);
        orderedDisabledPlugins.addAll(disabledPlugins);
        disabledPlugins.clear();
        disabledPlugins.addAll(orderedDisabledPlugins);
        return result;
    }

    @Override
    public PluginResult enableAll() {
        var enableOrder = new ArrayList<>(disabledPluginEnableOrder);
        getPlugins().stream()
                .filter(plugin -> !plugin.isEnabled() && !isIgnored(plugin))
                .map(Plugin::getName)
                .filter(pluginName -> enableOrder.stream().noneMatch(pluginName::equalsIgnoreCase))
                .forEach(enableOrder::add);
        disabledPlugins.stream()
                .filter(pluginName -> enableOrder.stream().noneMatch(pluginName::equalsIgnoreCase))
                .forEach(enableOrder::add);

        var allSuccessful = true;
        for (var pluginName : enableOrder) {
            if (isIgnored(pluginName)) continue;

            var plugin = getPluginByName(pluginName);
            if (plugin != null && plugin.isEnabled()) continue;

            var result = plugin == null ? enable(pluginName) : enable(plugin);
            if (!result.success()) allSuccessful = false;
        }

        if (allSuccessful) disabledPluginEnableOrder.clear();
        return new PluginResult(allSuccessful, "plugins.enabled-all");
    }

    private void captureDisabledPluginEnableOrder(boolean reset) {
        if (reset) disabledPluginEnableOrder.clear();
        if (!disabledPluginEnableOrder.isEmpty()) return;

        getPlugins().stream()
                .filter(plugin -> !isIgnored(plugin))
                .map(Plugin::getName)
                .forEach(disabledPluginEnableOrder::add);
    }

    @Override
    public List<String> getDisabledPluginNames(boolean fullName) {
        var disabledPluginNames = new ArrayList<>(super.getDisabledPluginNames(fullName));
        disabledPlugins.stream()
                .filter(pluginName -> disabledPluginNames.stream().noneMatch(pluginName::equalsIgnoreCase))
                .forEach(disabledPluginNames::add);
        return disabledPluginNames;
    }

    /**
     * Loads and enables a plugin.
     *
     * @param name plugin's name
     * @return status message
     */
    @Override
    public PluginResult load(String name) {
        var pluginFile = findPluginFile(name);
        if (pluginFile == null) return new PluginResult(false, "load.cannot-find");

        var validationResult = validatePluginFile(pluginFile);
        if (!validationResult.success()) return validationResult;

        PlugManBukkit.getInstance().getLogger().info("Attempting to load " + pluginFile.getPath());

        var paperPlugin = isPaperPlugin(pluginFile);
        var target = paperPlugin ? loadPaperPlugin(pluginFile) : loadPluginWithPaper(pluginFile);
        if (target == null && !paperPlugin) {
            target = loadAndEnablePlugin(pluginFile, true);
        }
        if (target == null) return new PluginResult(false, "load.invalid-plugin");

        var loadedPluginName = target.getName();
        restorePluginLoadPosition(target);
        scheduleCommandLoading();
        PlugManBukkit.getInstance().getFilePluginMap().put(pluginFile.getName(), loadedPluginName);
        disabledPlugins.removeIf(pluginName -> pluginName.equalsIgnoreCase(loadedPluginName));
        if (disabledPlugins.isEmpty() && getPlugins().stream()
                .noneMatch(plugin -> !plugin.isEnabled() && !isIgnored(plugin)))
            disabledPluginEnableOrder.clear();

        return new PluginResult(true, "load.loaded");
    }

    private String getDisabledPluginName(String pluginName) {
        return disabledPlugins.stream()
                .filter(disabledPlugin -> disabledPlugin.equalsIgnoreCase(pluginName))
                .findFirst()
                .orElse(null);
    }

    private static String normalizePluginName(String pluginName) {
        return pluginName.toLowerCase(Locale.ROOT);
    }


    private PluginResult validatePluginFile(File pluginFile) {
        var pluginDir = new File("plugins");
        if (!pluginDir.isDirectory()) return new PluginResult(false, "load.plugin-directory");

        if (!pluginFile.isFile()) return new PluginResult(false, "load.cannot-find");

        return new PluginResult(true, "validation.success");
    }

    private Plugin loadPaperPlugin(File pluginFile) {
        try {
            return new PaperRuntimePluginLoader().load(pluginFile);
        } catch (Throwable exception) {
            PlugManBukkit.getInstance().getLogger().log(Level.SEVERE,
                    "Failed to load Paper plugin: " + pluginFile.getName(), exception);
            return null;
        }
    }

    private Plugin loadPluginWithPaper(File pluginFile) {
        try {
            var paper = ClassAccessor.getClass("io.papermc.paper.plugin.manager.PaperPluginManagerImpl");
            if (paper == null) return null;

            var paperPluginManagerImpl = MethodAccessor.invoke(paper, "getInstance", null);

            var instanceManager = FieldAccessor.getValue(paperPluginManagerImpl.getClass(), "instanceManager", paperPluginManagerImpl);

            var target = MethodAccessor.<org.bukkit.plugin.Plugin>invoke(instanceManager.getClass(), "loadPlugin", instanceManager, new Class<?>[]{Path.class}, pluginFile.toPath());

            MethodAccessor.invoke(instanceManager.getClass(), "enablePlugin", instanceManager, new Class<?>[]{org.bukkit.plugin.Plugin.class}, target);

            return new BukkitPlugin(target);
        } catch (Exception ignore) {
            // Paper most likely not loaded
            return null;
        }
    }


    @Override
    protected synchronized void scheduleCommandLoading() {
        if (isFolia()) {
            var foliaLib = new com.tcoded.folialib.FoliaLib(PlugManBukkit.getInstance());
            foliaLib.getScheduler().runLater(this::syncCommands, 500, TimeUnit.MILLISECONDS);
        } else super.scheduleCommandLoading();
    }


    /**
     * Unload a plugin.
     *
     * @param plugin the plugin to unload
     * @return the message to send to the user.
     */
    @Override
    public synchronized PluginResult unload(Plugin plugin) {
        rememberPluginLoadPosition(plugin);
        var out = unloadWithPaper(plugin);
        if (!out.second().success()) return out.second();

        closeClassLoader(plugin);
        cleanupPaperPluginManager(plugin);
        System.gc();

        return new PluginResult(true, "unload.unloaded");
    }

    protected void rememberPluginLoadPosition(Plugin plugin) {
        try {
            var plugins = getPaperPluginList();
            var position = plugins.indexOf(plugin.<org.bukkit.plugin.Plugin>getHandle());
            if (position >= 0) pluginLoadPositions.put(normalizePluginName(plugin.getName()), position);
        } catch (Exception exception) {
            PlugManBukkit.getInstance().getLogger().log(Level.WARNING,
                    "Failed to remember load position for plugin: " + plugin.getName(), exception);
        }
    }

    private void restorePluginLoadPosition(Plugin plugin) {
        var position = pluginLoadPositions.remove(normalizePluginName(plugin.getName()));
        if (position == null) return;

        try {
            var plugins = getPaperPluginList();
            var pluginHandle = plugin.<org.bukkit.plugin.Plugin>getHandle();
            if (!plugins.remove(pluginHandle)) return;
            plugins.add(Math.min(position, plugins.size()), pluginHandle);
        } catch (Exception exception) {
            PlugManBukkit.getInstance().getLogger().log(Level.WARNING,
                    "Failed to restore load position for plugin: " + plugin.getName(), exception);
        }
    }

    private List<org.bukkit.plugin.Plugin> getPaperPluginList() throws Exception {
        var paperPluginManagerClass = ClassAccessor.getClass("io.papermc.paper.plugin.manager.PaperPluginManagerImpl");
        if (paperPluginManagerClass == null)
            throw new ClassNotFoundException("PaperPluginManagerImpl not found");

        var paperPluginManager = MethodAccessor.invoke(paperPluginManagerClass, "getInstance", null);
        var instanceManager = FieldAccessor.getValue(
                paperPluginManager.getClass(), "instanceManager", paperPluginManager);
        return FieldAccessor.getValue(instanceManager.getClass(), "plugins", instanceManager);
    }

    public Tuple<CommonUnloadData, PluginResult> unloadWithPaper(Plugin plugin) {
        if (!handleGentleUnload(plugin)) return new Tuple<>(null, new PluginResult(false, "unload.gentle-failed"));

        var unloadData = extractPluginManagerData(plugin);
        if (unloadData == null) return new Tuple<>(null, new PluginResult(false, "unload.failed"));

        cleanupListeners(plugin, unloadData);
        cleanupCommands(plugin, unloadData);
        removeFromPluginLists(plugin, unloadData);

        return new Tuple<>(unloadData, new PluginResult(true, "unload.common-success"));
    }

    private void cleanupListeners(Plugin plugin, CommonUnloadData data) {
        if (data.listeners() == null || !data.reloadListeners()) return;
        data.listeners().values().forEach(set -> set.removeIf(value -> value.getPlugin() == plugin.getHandle()));
    }

    @Override
    protected void cleanupCommands(Plugin plugin, CommonUnloadData data) {
        if (data.commandMap() != null) {
            var modifiedKnownCommands = data.commands();
            var pluginCommands = getCommandsFromPlugin(plugin);

            pluginCommands.forEach(entry -> {
                var command = entry.getValue().<Command>getHandle();

                command.unregister(data.commandMap());
                modifiedKnownCommands.remove(entry.getKey());
            });
        }

        cleanupLifecycleCommands(plugin);
        syncCommands();
    }

    private void cleanupLifecycleCommands(Plugin plugin) {
        try {
            PaperCommandRegistrationTracker.removeAndRestore(plugin.getName());
        } catch (Exception exception) {
            PlugManBukkit.getInstance().getLogger().log(Level.SEVERE,
                    "Failed to clean up lifecycle commands for plugin: " + plugin.getName(), exception);
        }
    }

    private void cleanupPaperPluginManager(Plugin plugin) {
        try {
            var paper = ClassAccessor.getClass("io.papermc.paper.plugin.manager.PaperPluginManagerImpl");
            if (paper == null) return;

            var paperPluginManagerImpl = MethodAccessor.invoke(paper, "getInstance", null);

            var instanceManager = FieldAccessor.getValue(paperPluginManagerImpl.getClass(), "instanceManager", paperPluginManagerImpl);

            var lookupNames = FieldAccessor.<Map<String, org.bukkit.plugin.Plugin>>getValue(instanceManager.getClass(), "lookupNames", instanceManager);

            MethodAccessor.invoke(instanceManager.getClass(), "disablePlugin", instanceManager, new Class<?>[]{org.bukkit.plugin.Plugin.class}, plugin);

            var pluginHandle = plugin.<org.bukkit.plugin.Plugin>getHandle();
            lookupNames.entrySet().removeIf(entry -> entry.getValue() == pluginHandle);

            var pluginList = FieldAccessor.<List<org.bukkit.plugin.Plugin>>getValue(instanceManager.getClass(), "plugins", instanceManager);
            pluginList.remove(pluginHandle);
        } catch (Exception ignore) {
            // Paper most likely not loaded
        }
    }
}
