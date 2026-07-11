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

import com.mojang.brigadier.tree.CommandNode;
import core.com.rylinaux.plugman.util.reflection.ClassAccessor;
import core.com.rylinaux.plugman.util.reflection.FieldAccessor;
import core.com.rylinaux.plugman.util.reflection.MethodAccessor;
import io.papermc.paper.command.brigadier.APICommandMeta;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.PaperCommands;
import io.papermc.paper.command.brigadier.bukkit.BukkitCommandNode;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventOwner;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventRunner;
import io.papermc.paper.plugin.lifecycle.event.registrar.RegistrarEventImpl;
import io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEventType;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.plugin.manager.PaperPluginManagerImpl;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tracks dispatcher nodes displaced by dynamically registered Paper commands.
 */
public final class PaperCommandRegistrationTracker {

    private static final Map<String, Map<String, CommandNode<CommandSourceStack>>> DISPLACED_COMMANDS = new LinkedHashMap<>();
    private static Map<String, CommandNode<CommandSourceStack>> baselineCommands;
    private static Map<String, CommandNode<CommandSourceStack>> pluginEventBaselineCommands;

    private PaperCommandRegistrationTracker() {
    }

    public static synchronized void captureBaseline() {
        if (baselineCommands != null) return;

        try {
            baselineCommands = snapshot();
        } catch (IllegalStateException ignored) {
            // The dispatcher is not available until Paper finishes its command setup.
        }
    }

    public static synchronized void capturePluginEventBaseline() {
        if (pluginEventBaselineCommands != null) return;

        try {
            pluginEventBaselineCommands = snapshot();
        } catch (IllegalStateException ignored) {
            // The dispatcher is not available until Paper finishes its command setup.
        }
    }

    static synchronized Map<String, CommandNode<CommandSourceStack>> snapshot() {
        var snapshot = new HashMap<String, CommandNode<CommandSourceStack>>();
        for (var command : commandRoot().getChildren()) snapshot.put(command.getName(), command);
        return snapshot;
    }

    static synchronized void record(String pluginName, Map<String, CommandNode<CommandSourceStack>> before) {
        var displaced = new HashMap<String, CommandNode<CommandSourceStack>>();
        for (var command : commandRoot().getChildren()) {
            if (!isOwnedBy(command, pluginName)) continue;

            var previous = before.get(command.getName());
            if (previous != command) displaced.put(command.getName(), previous);
        }
        DISPLACED_COMMANDS.put(normalize(pluginName), displaced);
    }

    static synchronized void removeAndRestore(String pluginName) throws Exception {
        var normalizedPluginName = normalize(pluginName);
        var displaced = DISPLACED_COMMANDS.remove(normalizedPluginName);
        var dynamicallyTracked = displaced != null;

        var root = commandRoot();
        var commandsToRemove = new ArrayList<String>();
        for (var command : root.getChildren()) {
            if (isOwnedBy(command, pluginName)) commandsToRemove.add(command.getName());
        }

        // Startup-loaded Paper plugins predate per-plugin tracking. The manager captures the
        // vanilla/Bukkit dispatcher before Paper fires its startup lifecycle command event, so
        // use that baseline to restore labels displaced during startup.
        if (displaced == null) {
            displaced = new HashMap<>();
            if (baselineCommands != null) {
                for (var command : commandsToRemove) {
                    var baselineCommand = baselineCommands.get(command);
                    if (baselineCommand != null) displaced.put(command, baselineCommand);
                }
            }
        }

        // If another dynamically loaded plugin displaced this plugin's command, splice the
        // removed layer out so that plugin can later restore the next live command underneath.
        for (var otherPluginCommands : DISPLACED_COMMANDS.values()) {
            for (var entry : otherPluginCommands.entrySet()) {
                if (!isOwnedBy(entry.getValue(), pluginName)) continue;
                entry.setValue(displaced == null ? null : displaced.get(entry.getKey()));
            }
        }

        var commandNodeClass = ClassAccessor.getClass("com.mojang.brigadier.tree.CommandNode");
        if (commandNodeClass == null) return;
        for (var command : commandsToRemove)
            MethodAccessor.invoke(commandNodeClass, "removeCommand", root,
                    new Class<?>[]{String.class}, command);

        for (var entry : displaced.entrySet()) {
            if (root.getChild(entry.getKey()) != null) continue;

            var previous = entry.getValue();
            if (previous == null || !isRestorable(previous, pluginName)) continue;
            root.addChild(previous);
        }

        if (!dynamicallyTracked) rebuildLifecycleCommands(pluginName);
    }

    private static void rebuildLifecycleCommands(String unloadingPlugin) throws Exception {
        var dispatcherBeforeRebuild = snapshot();
        var displacedBeforeRebuild = new LinkedHashMap<>(DISPLACED_COMMANDS);

        try {
            rebuildLifecycleCommandsUnsafe(unloadingPlugin);
        } catch (Throwable throwable) {
            restoreDispatcher(dispatcherBeforeRebuild);
            DISPLACED_COMMANDS.clear();
            DISPLACED_COMMANDS.putAll(displacedBeforeRebuild);
            if (throwable instanceof Exception exception) throw exception;
            if (throwable instanceof Error error) throw error;
            throw new RuntimeException(throwable);
        }
    }

    private static void rebuildLifecycleCommandsUnsafe(String unloadingPlugin) throws Exception {
        var dynamicPluginNames = new ArrayList<>(DISPLACED_COMMANDS.keySet());
        var root = commandRoot();
        var commandsToRemove = new ArrayList<String>();
        for (var command : root.getChildren()) {
            if (ownerName(command) != null) commandsToRemove.add(command.getName());
        }

        var commandNodeClass = ClassAccessor.getClass("com.mojang.brigadier.tree.CommandNode");
        if (commandNodeClass == null) return;
        for (var command : commandsToRemove)
            MethodAccessor.invoke(commandNodeClass, "removeCommand", root,
                    new Class<?>[]{String.class}, command);

        restoreBaselineNodes(root, baselineCommands, false);
        restoreBaselineNodes(root, pluginEventBaselineCommands, true);
        restoreKnownBukkitCommands(root);

        var loadedPluginNames = new LinkedHashMap<String, String>();
        for (var plugin : PaperPluginManagerImpl.getInstance().getPlugins()) {
            if (plugin.getName().equalsIgnoreCase(unloadingPlugin)) continue;
            loadedPluginNames.put(normalize(plugin.getName()), plugin.getName());
        }

        var loadedDynamicPlugins = new ArrayList<String>();
        for (var dynamicPluginName : dynamicPluginNames) {
            var loadedName = loadedPluginNames.get(dynamicPluginName);
            if (loadedName != null) loadedDynamicPlugins.add(loadedName);
        }

        var dynamicPluginKeys = new HashSet<String>();
        loadedDynamicPlugins.stream().map(PaperCommandRegistrationTracker::normalize)
                .forEach(dynamicPluginKeys::add);
        var startupPluginKeys = new LinkedHashSet<>(loadedPluginNames.keySet());
        startupPluginKeys.removeAll(dynamicPluginKeys);

        DISPLACED_COMMANDS.clear();
        replayCommandLifecycle(BootstrapContext.class, startupPluginKeys);
        replayCommandLifecycle(org.bukkit.plugin.Plugin.class, startupPluginKeys);

        for (var dynamicPluginName : loadedDynamicPlugins) {
            var before = snapshot();
            var pluginKey = Set.of(normalize(dynamicPluginName));
            replayCommandLifecycle(BootstrapContext.class, pluginKey);
            replayCommandLifecycle(org.bukkit.plugin.Plugin.class, pluginKey);
            record(dynamicPluginName, before);
        }
    }

    private static void restoreDispatcher(Map<String, CommandNode<CommandSourceStack>> snapshot) {
        try {
            var root = commandRoot();
            var commandNodeClass = ClassAccessor.getClass("com.mojang.brigadier.tree.CommandNode");
            if (commandNodeClass == null) return;

            var commandsToRemove = root.getChildren().stream().map(CommandNode::getName).toList();
            for (var command : commandsToRemove)
                MethodAccessor.invoke(commandNodeClass, "removeCommand", root,
                        new Class<?>[]{String.class}, command);
            snapshot.values().forEach(root::addChild);
        } catch (Exception ignored) {
        }
    }

    private static void restoreKnownBukkitCommands(
            com.mojang.brigadier.tree.RootCommandNode<CommandSourceStack> root) {
        for (var entry : Bukkit.getCommandMap().getKnownCommands().entrySet()) {
            if (root.getChild(entry.getKey()) == null)
                root.addChild(BukkitCommandNode.of(entry.getKey(), entry.getValue()));
        }
    }

    private static void restoreBaselineNodes(
            com.mojang.brigadier.tree.RootCommandNode<CommandSourceStack> root,
            Map<String, CommandNode<CommandSourceStack>> baseline,
            boolean onlyUnowned) throws Exception {
        if (baseline == null) return;

        for (var entry : baseline.entrySet()) {
            if (onlyUnowned && ownerName(entry.getValue()) != null) continue;
            if (onlyUnowned && entry.getValue() instanceof BukkitCommandNode bukkitCommandNode
                    && !Bukkit.getCommandMap().getKnownCommands().containsValue(bukkitCommandNode.getBukkitCommand()))
                continue;

            var current = root.getChild(entry.getKey());
            if (current == entry.getValue()) continue;
            if (current != null) {
                if (!onlyUnowned) continue;
                var commandNodeClass = ClassAccessor.getClass("com.mojang.brigadier.tree.CommandNode");
                if (commandNodeClass == null) continue;
                MethodAccessor.invoke(commandNodeClass, "removeCommand", root,
                        new Class<?>[]{String.class}, entry.getKey());
            }
            root.addChild(entry.getValue());
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void replayCommandLifecycle(
            Class<? extends LifecycleEventOwner> ownerType, Set<String> pluginNames) {
        PaperCommands.INSTANCE.setValid();
        try {
            var event = new RegistrarEventImpl.ReloadableImpl(
                    PaperCommands.INSTANCE, ownerType, ReloadableRegistrarEvent.Cause.RELOAD);
            LifecycleEventRunner.INSTANCE.callEvent(
                    (LifecycleEventType) LifecycleEvents.COMMANDS,
                    event,
                    owner -> ownerType.isInstance(owner)
                            && pluginNames.contains(normalize(
                            ((LifecycleEventOwner) owner).getPluginMeta().getName())));
        } finally {
            PaperCommands.INSTANCE.invalidate();
        }
    }

    static synchronized void restoreSnapshot(String pluginName,
                                             Map<String, CommandNode<CommandSourceStack>> before) {
        try {
            DISPLACED_COMMANDS.remove(normalize(pluginName));
            var root = commandRoot();
            var commandsToRemove = new ArrayList<String>();
            for (var command : root.getChildren()) {
                if (isOwnedBy(command, pluginName)) commandsToRemove.add(command.getName());
            }

            var commandNodeClass = ClassAccessor.getClass("com.mojang.brigadier.tree.CommandNode");
            if (commandNodeClass == null) return;
            for (var command : commandsToRemove)
                MethodAccessor.invoke(commandNodeClass, "removeCommand", root,
                        new Class<?>[]{String.class}, command);

            for (var entry : before.entrySet()) {
                if (root.getChild(entry.getKey()) == null) root.addChild(entry.getValue());
            }
        } catch (Exception ignored) {
        }
    }

    private static com.mojang.brigadier.tree.RootCommandNode<CommandSourceStack> commandRoot() {
        return PaperCommands.INSTANCE.getDispatcherInternal().getRoot();
    }

    private static boolean isRestorable(CommandNode<CommandSourceStack> command, String unloadingPlugin) {
        var ownerName = ownerName(command);
        if (ownerName == null) return true;
        if (ownerName.equalsIgnoreCase(unloadingPlugin)) return false;
        return PaperPluginManagerImpl.getInstance().getPlugin(ownerName) != null;
    }

    private static boolean isOwnedBy(CommandNode<CommandSourceStack> command, String pluginName) {
        var ownerName = ownerName(command);
        return ownerName != null && ownerName.equalsIgnoreCase(pluginName);
    }

    private static String ownerName(CommandNode<CommandSourceStack> command) {
        if (command == null) return null;

        try {
            var commandNodeClass = ClassAccessor.getClass("com.mojang.brigadier.tree.CommandNode");
            if (commandNodeClass == null) return null;

            var commandMeta = FieldAccessor.<APICommandMeta>getValue(commandNodeClass, "apiCommandMeta", command);
            if (commandMeta == null || commandMeta.pluginMeta() == null) return null;
            return commandMeta.pluginMeta().getName();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normalize(String pluginName) {
        return pluginName.toLowerCase(Locale.ROOT);
    }
}
