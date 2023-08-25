package net.frankheijden.serverutils.bukkit.managers;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import net.frankheijden.serverutils.bukkit.entities.BukkitPluginDescription;
import net.frankheijden.serverutils.bukkit.events.BukkitPluginDisableEvent;
import net.frankheijden.serverutils.bukkit.events.BukkitPluginEnableEvent;
import net.frankheijden.serverutils.bukkit.events.BukkitPluginLoadEvent;
import net.frankheijden.serverutils.bukkit.events.BukkitPluginUnloadEvent;
import net.frankheijden.serverutils.bukkit.reflection.RCommandDispatcher;
import net.frankheijden.serverutils.bukkit.reflection.RCommandMap;
import net.frankheijden.serverutils.bukkit.reflection.RCraftServer;
import net.frankheijden.serverutils.bukkit.reflection.RCraftingManager;
import net.frankheijden.serverutils.bukkit.reflection.RJavaPlugin;
import net.frankheijden.serverutils.bukkit.reflection.RJavaPluginLoader;
import net.frankheijden.serverutils.bukkit.reflection.RPluginClassLoader;
import net.frankheijden.serverutils.bukkit.reflection.RSimplePluginManager;
import net.frankheijden.serverutils.common.entities.results.CloseablePluginResults;
import net.frankheijden.serverutils.common.entities.results.PluginResults;
import net.frankheijden.serverutils.common.entities.results.Result;
import net.frankheijden.serverutils.common.entities.exceptions.InvalidPluginDescriptionException;
import net.frankheijden.serverutils.common.events.PluginEvent;
import net.frankheijden.serverutils.common.managers.AbstractPluginManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoader;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.UnknownDependencyException;
import org.yaml.snakeyaml.error.YAMLException;

public class BukkitPluginManager extends AbstractPluginManager<Plugin, BukkitPluginDescription> {

    private static BukkitPluginManager instance;

    public BukkitPluginManager() {
        instance = this;
    }

    public static BukkitPluginManager get() {
        return instance;
    }

    @Override
    public PluginResults<Plugin> loadPluginDescriptions(List<BukkitPluginDescription> descriptions) {
        PluginResults<Plugin> pluginResults = new PluginResults<>();

        List<Plugin> plugins = new ArrayList<>();
        for (BukkitPluginDescription description : descriptions) {
            String pluginId = description.getId();

            Plugin plugin;
            try {
                plugin = Bukkit.getPluginManager().loadPlugin(description.getFile());
            } catch (InvalidDescriptionException ex) {
                return pluginResults.addResult(pluginId, Result.INVALID_DESCRIPTION);
            } catch (UnknownDependencyException ex) {
                return pluginResults.addResult(pluginId, Result.UNKNOWN_DEPENDENCY,
                        "dependency", ex.getMessage()
                );
            } catch (InvalidPluginException ex) {
                if (ex.getCause() instanceof IllegalArgumentException) {
                    IllegalArgumentException e = (IllegalArgumentException) ex.getCause();
                    if (e.getMessage().equalsIgnoreCase("Plugin already initialized!")) {
                        return pluginResults.addResult(pluginId, Result.ALREADY_ENABLED);
                    }
                }
                ex.printStackTrace();
                return pluginResults.addResult(pluginId, Result.ERROR);
            }

            if (plugin == null) return pluginResults.addResult(pluginId, Result.INVALID_PLUGIN);

            if (RSimplePluginManager.MANAGED_BY_PAPER_INSTANCE_MANAGER) {
                Bukkit.getPluginManager().callEvent(new BukkitPluginLoadEvent(plugin, PluginEvent.Stage.POST));
                pluginResults.addResult(pluginId, plugin);
                continue;
            }

            plugins.add(plugin);
            Bukkit.getPluginManager().callEvent(new BukkitPluginLoadEvent(plugin, PluginEvent.Stage.PRE));
        }

        if (RSimplePluginManager.MANAGED_BY_PAPER_INSTANCE_MANAGER) {
            return pluginResults;
        }

        for (Plugin plugin : plugins) {
            String pluginId = getPluginId(plugin);
            try {
                plugin.onLoad();
            } catch (Throwable th) {
                th.printStackTrace();
                return pluginResults.addResult(pluginId, Result.ERROR);
            }

            Bukkit.getPluginManager().callEvent(new BukkitPluginLoadEvent(plugin, PluginEvent.Stage.POST));
            pluginResults.addResult(pluginId, plugin);
        }

        return pluginResults;
    }

    @Override
    public PluginResults<Plugin> disableOrderedPlugins(List<Plugin> plugins) {
        PluginResults<Plugin> disableResults = new PluginResults<>();

        Set<String> removedCommands = new HashSet<>();
        for (Plugin plugin : plugins) {
            String pluginId = getPluginId(plugin);
            if (!isPluginEnabled(pluginId)) return disableResults.addResult(pluginId, Result.ALREADY_DISABLED);

            Bukkit.getPluginManager().callEvent(new BukkitPluginDisableEvent(plugin, PluginEvent.Stage.PRE));
            Map<String, ? extends Command> pluginCommands = getPluginCommands(plugin);

            try {
                Bukkit.getPluginManager().disablePlugin(plugin);
                RCraftingManager.removeRecipesFor(plugin);
            } catch (Exception ex) {
                ex.printStackTrace();
                return disableResults.addResult(pluginId, Result.ERROR);
            }

            unregisterCommands(pluginCommands);
            removedCommands.addAll(pluginCommands.keySet());
            Bukkit.getPluginManager().callEvent(new BukkitPluginDisableEvent(plugin, PluginEvent.Stage.POST));

            disableResults.addResult(pluginId, plugin);
        }

        RCraftServer.syncCommands(removedCommands);
        return disableResults;
    }

    @Override
    public CloseablePluginResults<Plugin> unloadOrderedPlugins(List<Plugin> plugins) {
        CloseablePluginResults<Plugin> unloadResults = new CloseablePluginResults<>();

        for (Plugin plugin : plugins) {
            String pluginId = getPluginId(plugin);
            Bukkit.getPluginManager().callEvent(new BukkitPluginUnloadEvent(plugin, PluginEvent.Stage.PRE));

            RCraftingManager.removeRecipesFor(plugin);
            unregisterCommands(plugin);

            List<Closeable> closeables = new ArrayList<>();
            try {
                RSimplePluginManager.getPlugins(Bukkit.getPluginManager()).remove(plugin);
                RSimplePluginManager.removeLookupName(Bukkit.getPluginManager(), pluginId);

                ClassLoader classLoader = plugin.getClass().getClassLoader();
                PluginLoader loader = RPluginClassLoader.getLoader(classLoader);
                Map<String, Class<?>> classes = RPluginClassLoader.getClasses(classLoader);
                RJavaPluginLoader.removeClasses(loader, classes.keySet());

                RPluginClassLoader.clearClassLoader(classLoader);
                RJavaPlugin.clearJavaPlugin(plugin);

                addIfInstance(closeables, RPluginClassLoader.getLibraryLoader(classLoader));
                addIfInstance(closeables, classLoader);
            } catch (Exception ex) {
                ex.printStackTrace();
                return unloadResults.addResult(pluginId, Result.ERROR);
            }

            Bukkit.getPluginManager().callEvent(new BukkitPluginUnloadEvent(plugin, PluginEvent.Stage.POST));

            unloadResults.addResult(pluginId, plugin, closeables);
        }

        return unloadResults;
    }

    private static void addIfInstance(List<Closeable> list, Object obj) {
        if (obj instanceof Closeable) {
            list.add((Closeable) obj);
        }
    }

    @Override
    protected PluginResults<Plugin> enableOrderedPlugins(List<Plugin> plugins) {
        PluginResults<Plugin> enableResults = new PluginResults<>();
        PluginManager bukkitPluginManager = Bukkit.getPluginManager();
        for (Plugin plugin : plugins) {
            String pluginId = getPluginId(plugin);
            bukkitPluginManager.callEvent(new BukkitPluginEnableEvent(plugin, PluginEvent.Stage.PRE));
            bukkitPluginManager.enablePlugin(plugin);

            if (!bukkitPluginManager.isPluginEnabled(plugin.getName())) {
                return enableResults.addResult(pluginId, Result.ERROR);
            }
            bukkitPluginManager.callEvent(new BukkitPluginEnableEvent(plugin, PluginEvent.Stage.POST));
            enableResults.addResult(pluginId, plugin);
        }

        RCraftServer.syncCommands(Collections.emptySet());
        return enableResults;
    }

    @Override
    public boolean isPluginEnabled(String pluginId) {
        return Bukkit.getPluginManager().isPluginEnabled(pluginId);
    }

    /**
     * Retrieves all known commands registered to bukkit.
     * @return A map with all known commands.
     */
    public static Map<String, Command> getKnownCommands() {
        try {
            return RCommandMap.getKnownCommands(RCraftServer.getCommandMap());
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }
    }

    /**
     * Retrieves the commands associated to a plugin.
     */
    public static <C extends Command & PluginIdentifiableCommand> Map<String, C> getPluginCommands(Plugin plugin) {
        Map<String, C> commands = new HashMap<>();

        Map<String, Command> knownCommands = getKnownCommands();
        if (knownCommands == null) return commands;

        for (Map.Entry<String, Command> entry : knownCommands.entrySet()) {
            Command c = entry.getValue();
            if (c instanceof PluginIdentifiableCommand) {
                @SuppressWarnings("unchecked")
                C pc = (C) c;
                if (pc.getPlugin().getName().equals(plugin.getName())) {
                    commands.put(entry.getKey(), pc);
                }
            }
        }

        return commands;
    }

    /**
     * Unregisters all commands from the specified plugin.
     * @param plugin The plugin.
     */
    public static void unregisterCommands(Plugin plugin) {
        unregisterCommands(getPluginCommands(plugin));
    }

    private static void unregisterCommands(Map<String, ? extends Command> commands) {
        commands.values().forEach(c -> {
            if (c instanceof PluginCommand) {
                PluginCommand pluginCommand = (PluginCommand) c;
                pluginCommand.setExecutor(null);
                pluginCommand.setTabCompleter(null);
            }
            c.unregister(RCraftServer.getCommandMap());
        });
        RCommandDispatcher.removeCommands(commands.keySet());

        Map<String, Command> knownCommands = getKnownCommands();
        if (knownCommands == null) return;
        knownCommands.values().removeIf(c -> c.equals(commands.get(c.getName().toLowerCase())));
    }

    /**
     * Unregisters all the specified PluginCommand's.
     * @param pluginCommands The commands to unregister.
     * @deprecated Use {@link BukkitPluginManager#unregisterCommands(Map)} instead.
     */
    @Deprecated
    public static void unregisterCommands(Collection<? extends PluginCommand> pluginCommands) {
        Map<String, Command> knownCommands = getKnownCommands();
        if (knownCommands == null) return;

        Set<String> commands = new HashSet<>();
        for (PluginCommand pc : pluginCommands) {
            commands.add(pc.getName().toLowerCase());
            pc.setExecutor(null);
            pc.setTabCompleter(null);

            for (String alias : pc.getAliases()) {
                if (!pc.equals(Bukkit.getPluginCommand(alias))) continue;
                commands.add(alias);
            }
        }

        knownCommands.values().removeIf(c -> {
            if (commands.contains(c.getName().toLowerCase())) {
                c.unregister(RCraftServer.getCommandMap());
                return true;
            }
            return false;
        });

        RCommandDispatcher.removeCommands(commands);
        RCraftServer.updateCommands();
    }

    /**
     * Unregisters all the specified commands.
     */
    public static void unregisterCommands(String... commands) {
        Map<String, Command> map;
        try {
            map = RCommandMap.getKnownCommands(RCraftServer.getCommandMap());
        } catch (Exception ex) {
            ex.printStackTrace();
            return;
        }

        List<Command> unregisteredCommands = new ArrayList<>();
        for (String commandName : commands) {
            Command command = map.remove(commandName);
            if (command == null) continue;

            unregisteredCommands.add(command);
        }

        unregisterExactCommands(unregisteredCommands);
    }

    /**
     * Unregisters all specified commands exactly.
     * @param commands The commands to unregister.
     */
    public static void unregisterExactCommands(Collection<? extends Command> commands) {
        Map<String, Command> knownCommands = getKnownCommands();
        if (knownCommands == null) return;
        knownCommands.values().removeAll(commands);

        RCommandDispatcher.removeCommands(commands.stream().map(Command::getName).collect(Collectors.toList()));
        RCraftServer.updateCommands();
    }

    /**
     * Retrieves a command from the command map.
     * @param command The command string.
     * @return The command.
     */
    public static Command getCommand(String command) {
        Map<String, Command> knownCommands = getKnownCommands();
        if (knownCommands == null) return null;
        return knownCommands.get(command);
    }

    @Override
    public Optional<BukkitPluginDescription> getPluginDescription(File file) throws InvalidPluginDescriptionException {
        if (!file.exists()) return Optional.empty();

        try {
            return Optional.of(new BukkitPluginDescription(loadDescriptionFromJar(file), file));
        } catch (InvalidDescriptionException ex) {
            throw new InvalidPluginDescriptionException(ex);
        }
    }

    @Override
    public File getPluginFile(Plugin plugin) {
        return RJavaPlugin.getFile(plugin);
    }

    @Override
    public Optional<Plugin> getPlugin(String pluginName) {
        return Optional.ofNullable(Bukkit.getPluginManager().getPlugin(pluginName));
    }

    @Override
    public BukkitPluginDescription getLoadedPluginDescription(Plugin plugin) {
        return new BukkitPluginDescription(plugin.getDescription(), getPluginFile(plugin));
    }

    @Override
    public Plugin getInstance(Plugin plugin) {
        return plugin;
    }

    @Override
    public Set<String> getCommands() {
        Map<String, Command> knownCommands = getKnownCommands();
        if (knownCommands == null) return Collections.emptySet();
        return knownCommands.keySet();
    }

    @Override
    public List<Plugin> getPlugins() {
        return Arrays.asList(Bukkit.getPluginManager().getPlugins());
    }

    @Override
    public String getPluginId(Plugin plugin) {
        return plugin.getName();
    }

    // Copied from JavaPluginLoader#getPluginDescription
    private PluginDescriptionFile loadDescriptionFromJar(File file) throws InvalidDescriptionException {
        JarFile jar = null;
        InputStream stream = null;

        try {
            jar = new JarFile(file);
            JarEntry entry = jar.getJarEntry("plugin.yml");

            if (entry == null) {
                entry = jar.getJarEntry("paper-plugin.yml");
            }

            if (entry == null) {
                throw new InvalidDescriptionException(new FileNotFoundException("Jar does not contain plugin.yml"));
            }

            stream = jar.getInputStream(entry);

            return new PluginDescriptionFile(stream);
        } catch (IOException | YAMLException ex) {
            throw new InvalidDescriptionException(ex);
        } finally {
            if (jar != null) {
                try {
                    jar.close();
                } catch (IOException ignored) {
                }
            }
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
