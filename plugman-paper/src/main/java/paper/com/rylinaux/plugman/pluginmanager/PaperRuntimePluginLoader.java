package paper.com.rylinaux.plugman.pluginmanager;

/*-
 * #%L
 * PlugManX Core
 * %%
 * Copyright (C) 2010 - 2025 plugman-core
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

import bukkit.com.rylinaux.plugman.plugin.BukkitPlugin;
import com.google.common.graph.MutableGraph;
import com.mojang.brigadier.tree.CommandNode;
import core.com.rylinaux.plugman.file.PluginDescriptor;
import core.com.rylinaux.plugman.plugins.Plugin;
import core.com.rylinaux.plugman.util.reflection.FieldAccessor;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.PaperCommands;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.entrypoint.Entrypoint;
import io.papermc.paper.plugin.entrypoint.EntrypointHandler;
import io.papermc.paper.plugin.entrypoint.LaunchEntryPointHandler;
import io.papermc.paper.plugin.entrypoint.dependency.BootstrapMetaDependencyTree;
import io.papermc.paper.plugin.entrypoint.dependency.MetaDependencyTree;
import io.papermc.paper.plugin.entrypoint.dependency.SimpleMetaDependencyTree;
import io.papermc.paper.plugin.manager.PaperPluginManagerImpl;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventOwner;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventRunner;
import io.papermc.paper.plugin.lifecycle.event.registrar.RegistrarEventImpl;
import io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent;
import io.papermc.paper.plugin.lifecycle.event.types.AbstractLifecycleEventType;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEventType;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.plugin.provider.PluginProvider;
import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import io.papermc.paper.plugin.provider.classloader.PaperClassLoaderStorage;
import io.papermc.paper.plugin.provider.source.FileProviderSource;
import io.papermc.paper.plugin.provider.type.paper.PaperPluginParent;
import io.papermc.paper.plugin.storage.BootstrapProviderStorage;
import io.papermc.paper.plugin.storage.ProviderStorage;
import io.papermc.paper.plugin.storage.ServerPluginProviderStorage;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads a single Paper plugin through isolated provider storages.
 * Paper's built-in runtime storage intentionally accepts only Bukkit plugins,
 * so entering the global storages is not safe here: it would process every
 * provider that was already loaded during server startup.
 */
final class PaperRuntimePluginLoader {

    Plugin load(File pluginFile) throws Exception {
        var paperPluginManager = PaperPluginManagerImpl.getInstance();
        var serverStorage = new RuntimeServerPluginProviderStorage(
                paperPluginManager, paperPluginManager.getInstanceManagerGraph());
        var entrypointHandler = new RuntimeEntrypointHandler(new RuntimeBootstrapProviderStorage(), serverStorage);
        var providerSource = new FileProviderSource(path -> "File '" + path + "'");
        var bootstrapEntered = false;
        Map<String, CommandNode<CommandSourceStack>> commandSnapshot = null;

        try {
            var descriptor = PluginDescriptor.fromJar(pluginFile)
                    .orElseThrow(() -> new InvalidPluginException("The jar has no supported plugin descriptor"));
            if (paperPluginManager.getPlugin(descriptor.name()) != null)
                throw new InvalidPluginException("Plugin " + descriptor.name() + " is already loaded");

            Path preparedPath = providerSource.prepareContext(pluginFile.toPath());
            providerSource.registerProviders(entrypointHandler, preparedPath);

            var pluginProvider = entrypointHandler.pluginProvider();
            if (!(pluginProvider instanceof PaperPluginParent.PaperServerPluginProvider))
                throw new InvalidPluginException("The jar did not register a Paper plugin provider");

            var pluginName = pluginProvider.getMeta().getName();
            if (paperPluginManager.getPlugin(pluginName) != null)
                throw new InvalidPluginException("Plugin " + pluginName + " is already loaded");

            bootstrapEntered = true;
            entrypointHandler.enter(Entrypoint.BOOTSTRAPPER);
            entrypointHandler.enter(Entrypoint.PLUGIN);

            var loadedPlugin = serverStorage.loadedPlugin();
            if (loadedPlugin == null)
                throw new InvalidPluginException("Paper did not create a plugin instance for " + pluginName);

            paperPluginManager.enablePlugin(loadedPlugin);
            if (!loadedPlugin.isEnabled())
                throw new InvalidPluginException("Paper failed to enable plugin " + pluginName);

            commandSnapshot = PaperCommandRegistrationTracker.snapshot();
            if (entrypointHandler.hasBootstrapProvider())
                replayCommandLifecycle(pluginProvider, BootstrapContext.class);
            replayCommandLifecycle(pluginProvider, org.bukkit.plugin.Plugin.class);
            PaperCommandRegistrationTracker.record(pluginName, commandSnapshot);
            entrypointHandler.registerGlobally();
            return new BukkitPlugin(loadedPlugin);
        } catch (Throwable throwable) {
            var pluginProvider = entrypointHandler.pluginProvider();
            if (pluginProvider != null) {
                if (commandSnapshot != null)
                    PaperCommandRegistrationTracker.restoreSnapshot(
                            pluginProvider.getMeta().getName(), commandSnapshot);
                var loadedPlugin = serverStorage.loadedPlugin();
                if (loadedPlugin == null) serverStorage.rollback(pluginProvider);
                else rollbackLoadedPlugin(paperPluginManager, loadedPlugin);
                if (bootstrapEntered) cleanupLifecycleHandlers(pluginProvider.getMeta().getName());
            }
            entrypointHandler.unregisterGlobally();
            entrypointHandler.closeProviderResources();
            if (throwable instanceof Exception exception) throw exception;
            if (throwable instanceof Error error) throw error;
            throw new RuntimeException(throwable);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void replayCommandLifecycle(PluginProvider<JavaPlugin> pluginProvider,
                                        Class<? extends LifecycleEventOwner> ownerType) {
        try {
            PaperCommands.INSTANCE.setValid();
            var event = new RegistrarEventImpl.ReloadableImpl(
                    PaperCommands.INSTANCE, ownerType, ReloadableRegistrarEvent.Cause.RELOAD);
            LifecycleEventRunner.INSTANCE.callEvent(
                    (LifecycleEventType) LifecycleEvents.COMMANDS,
                    event,
                    owner -> ownerType.isInstance(owner)
                            && ((LifecycleEventOwner) owner).getPluginMeta().getName()
                            .equalsIgnoreCase(pluginProvider.getMeta().getName()));
        } catch (Throwable throwable) {
            pluginProvider.getLogger().error(
                    "Failed to register commands for dynamically loaded plugin " + pluginProvider.getMeta().getName(),
                    throwable);
        } finally {
            PaperCommands.INSTANCE.invalidate();
        }
    }

    private void rollbackLoadedPlugin(PaperPluginManagerImpl paperPluginManager, JavaPlugin plugin) {
        try {
            paperPluginManager.disablePlugin(plugin);

            var instanceManager = FieldAccessor.getValue(
                    paperPluginManager.getClass(), "instanceManager", paperPluginManager);
            if (instanceManager == null) return;

            var plugins = FieldAccessor.<List<org.bukkit.plugin.Plugin>>getValue(
                    instanceManager.getClass(), "plugins", instanceManager);
            if (plugins != null) plugins.remove(plugin);

            var lookupNames = FieldAccessor.<Map<String, org.bukkit.plugin.Plugin>>getValue(
                    instanceManager.getClass(), "lookupNames", instanceManager);
            if (lookupNames != null) lookupNames.entrySet().removeIf(entry -> entry.getValue() == plugin);

            var dependencyTree = FieldAccessor.<MetaDependencyTree>getValue(
                    instanceManager.getClass(), "dependencyTree", instanceManager);
            if (dependencyTree != null) {
                var pluginMeta = plugin.getPluginMeta();
                var dependencyGraph = dependencyTree.getGraph();
                var dependents = dependencyGraph.nodes().contains(pluginMeta.getName())
                        ? Set.copyOf(dependencyGraph.predecessors(pluginMeta.getName()))
                        : Set.<String>of();

                dependencyTree.remove(pluginMeta);
                dependents.forEach(dependent -> dependencyGraph.putEdge(dependent, pluginMeta.getName()));
            }
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void cleanupLifecycleHandlers(String pluginName) {
        try {
            var eventTypes = FieldAccessor.<List<?>>getValue(
                    LifecycleEventRunner.class, "lifecycleEventTypes", LifecycleEventRunner.INSTANCE);
            if (eventTypes == null) return;

            for (var eventType : eventTypes) {
                ((AbstractLifecycleEventType) eventType).removeMatching(registeredHandler -> {
                    var handler = (AbstractLifecycleEventType.RegisteredHandler) registeredHandler;
                    var owner = (LifecycleEventOwner) handler.owner();
                    return owner.getPluginMeta().getName().equalsIgnoreCase(pluginName);
                });
            }
        } catch (Exception ignored) {
        }
    }

    private static final class RuntimeEntrypointHandler implements EntrypointHandler {

        private final RuntimeBootstrapProviderStorage bootstrapStorage;
        private final RuntimeServerPluginProviderStorage pluginStorage;
        private PluginProvider<PluginBootstrap> bootstrapProvider;
        private PluginProvider<JavaPlugin> pluginProvider;
        private boolean bootstrapRegisteredGlobally;
        private boolean pluginRegisteredGlobally;

        private RuntimeEntrypointHandler(RuntimeBootstrapProviderStorage bootstrapStorage,
                                         RuntimeServerPluginProviderStorage pluginStorage) {
            this.bootstrapStorage = bootstrapStorage;
            this.pluginStorage = pluginStorage;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> void register(Entrypoint<T> entrypoint, PluginProvider<T> provider) {
            if (entrypoint == Entrypoint.BOOTSTRAPPER) {
                if (bootstrapProvider != null) throw new IllegalStateException("Plugin registered multiple bootstrappers");
                bootstrapProvider = (PluginProvider<PluginBootstrap>) provider;
                bootstrapStorage.register(bootstrapProvider);
                return;
            }

            if (entrypoint == Entrypoint.PLUGIN) {
                if (pluginProvider != null) throw new IllegalStateException("Jar registered multiple plugins");
                pluginProvider = (PluginProvider<JavaPlugin>) provider;
                pluginStorage.register(pluginProvider);
                return;
            }

            throw new IllegalArgumentException("Unsupported runtime entrypoint " + entrypoint);
        }

        @Override
        public void enter(Entrypoint<?> entrypoint) {
            storage(entrypoint).enter();
        }

        private ProviderStorage<?> storage(Entrypoint<?> entrypoint) {
            if (entrypoint == Entrypoint.BOOTSTRAPPER) return bootstrapStorage;
            if (entrypoint == Entrypoint.PLUGIN) return pluginStorage;
            throw new IllegalArgumentException("Unsupported runtime entrypoint " + entrypoint);
        }

        private PluginProvider<JavaPlugin> pluginProvider() {
            return pluginProvider;
        }

        private boolean hasBootstrapProvider() {
            return bootstrapProvider != null;
        }

        private void registerGlobally() {
            if (bootstrapProvider != null) {
                LaunchEntryPointHandler.INSTANCE.register(Entrypoint.BOOTSTRAPPER, bootstrapProvider);
                bootstrapRegisteredGlobally = true;
            }
            LaunchEntryPointHandler.INSTANCE.register(Entrypoint.PLUGIN, pluginProvider);
            pluginRegisteredGlobally = true;
        }

        private void unregisterGlobally() {
            if (pluginRegisteredGlobally) {
                removeGlobalProvider(Entrypoint.PLUGIN, pluginProvider);
                pluginRegisteredGlobally = false;
            }
            if (bootstrapRegisteredGlobally) {
                removeGlobalProvider(Entrypoint.BOOTSTRAPPER, bootstrapProvider);
                bootstrapRegisteredGlobally = false;
            }
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private void removeGlobalProvider(Entrypoint entrypoint, PluginProvider<?> provider) {
            try {
                var storage = LaunchEntryPointHandler.INSTANCE.get(entrypoint);
                var providers = FieldAccessor.<List<?>>getValue(
                        io.papermc.paper.plugin.storage.SimpleProviderStorage.class,
                        "providers", storage);
                if (providers != null) providers.remove(provider);
            } catch (Exception ignored) {
            }
        }

        private void closeProviderResources() {
            var provider = pluginProvider != null ? pluginProvider : bootstrapProvider;
            if (provider == null || provider.file() == null) return;

            try {
                var parent = FieldAccessor.getValue(provider.getClass(), "this$0", provider);
                if (parent != null) {
                    var classLoader = FieldAccessor.<ConfiguredPluginClassLoader>getValue(
                            parent.getClass(), "classLoader", parent);
                    if (classLoader != null) {
                        PaperClassLoaderStorage.instance().unregisterClassloader(classLoader);
                        classLoader.close();
                        return;
                    }
                }
            } catch (Exception ignored) {
            }

            try {
                provider.file().close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class RuntimeBootstrapProviderStorage extends BootstrapProviderStorage {

        @Override
        public MetaDependencyTree createDependencyTree() {
            var dependencyTree = new BootstrapMetaDependencyTree();
            for (var provider : LaunchEntryPointHandler.INSTANCE.get(Entrypoint.BOOTSTRAPPER).getRegisteredProviders())
                dependencyTree.add(provider);
            return dependencyTree;
        }
    }

    private static final class RuntimeServerPluginProviderStorage extends ServerPluginProviderStorage {

        private final MutableGraph<String> dependencyGraph;
        private final PaperPluginManagerImpl paperPluginManager;
        private RuntimeServerDependencyTree dependencyTree;
        private JavaPlugin loadedPlugin;

        private RuntimeServerPluginProviderStorage(PaperPluginManagerImpl paperPluginManager,
                                                   MutableGraph<String> dependencyGraph) {
            this.paperPluginManager = paperPluginManager;
            this.dependencyGraph = dependencyGraph;
        }

        @Override
        public MetaDependencyTree createDependencyTree() {
            dependencyTree = new RuntimeServerDependencyTree(paperPluginManager, dependencyGraph);
            return dependencyTree;
        }

        @Override
        public void processProvided(PluginProvider<JavaPlugin> provider, JavaPlugin provided) {
            super.processProvided(provider, provided);
            loadedPlugin = provided;
        }

        private JavaPlugin loadedPlugin() {
            return loadedPlugin;
        }

        private void rollback(PluginProvider<JavaPlugin> provider) {
            if (dependencyTree != null) dependencyTree.remove(provider);
        }
    }

    private static final class RuntimeServerDependencyTree extends SimpleMetaDependencyTree {

        private final PaperPluginManagerImpl paperPluginManager;

        private RuntimeServerDependencyTree(PaperPluginManagerImpl paperPluginManager,
                                            MutableGraph<String> dependencyGraph) {
            super(dependencyGraph);
            this.paperPluginManager = paperPluginManager;
        }

        @Override
        public boolean hasDependency(String pluginIdentifier) {
            return paperPluginManager.getPlugin(pluginIdentifier) != null;
        }

        @Override
        public void remove(PluginMeta pluginMeta) {
            var dependents = graph.nodes().contains(pluginMeta.getName())
                    ? Set.copyOf(graph.predecessors(pluginMeta.getName()))
                    : Set.<String>of();

            super.remove(pluginMeta);
            dependents.forEach(dependent -> graph.putEdge(dependent, pluginMeta.getName()));
        }
    }
}
