package net.frankheijden.serverutils.bukkit.reflection;

import dev.frankheijden.minecraftreflection.MinecraftReflection;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.SimplePluginManager;

public class RSimplePluginManager {

    private static final MinecraftReflection reflection = MinecraftReflection.of(SimplePluginManager.class);
    private static final Object PAPER_INSTANCE_MANAGER;
    private static final Field PLUGINS_FIELD;
    private static final Field LOOKUP_NAMES_FIELD;

    public static final boolean MANAGED_BY_PAPER_INSTANCE_MANAGER;

    static {
        Object paperInstanceManager;
        Field pluginsField;
        Field lookupNamesField;

        try {
            Object paperPluginManager = RCraftServer.getReflection().get(Bukkit.getServer(), "paperPluginManager");
            Field instanceManagerField = paperPluginManager.getClass().getDeclaredField("instanceManager");
            instanceManagerField.setAccessible(true);
            paperInstanceManager = instanceManagerField.get(paperPluginManager);
            pluginsField = paperInstanceManager.getClass().getDeclaredField("plugins");
            pluginsField.setAccessible(true);
            lookupNamesField = paperInstanceManager.getClass().getDeclaredField("lookupNames");
            lookupNamesField.setAccessible(true);
        } catch (Throwable ignored) {
            paperInstanceManager = null;
            pluginsField = null;
            lookupNamesField = null;
        }

        PAPER_INSTANCE_MANAGER = paperInstanceManager;
        PLUGINS_FIELD = pluginsField;
        LOOKUP_NAMES_FIELD = lookupNamesField;
        MANAGED_BY_PAPER_INSTANCE_MANAGER = paperInstanceManager != null;
    }

    public static MinecraftReflection getReflection() {
        return reflection;
    }

    @SuppressWarnings("unchecked")
    public static List<Plugin> getPlugins(Object manager) {
        if (PAPER_INSTANCE_MANAGER == null) {
            return reflection.get(manager, "plugins");
        }

        try {
            return (List<Plugin>) PLUGINS_FIELD.get(PAPER_INSTANCE_MANAGER);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Removes the lookup name of the plugin.
     * This ensures the plugin cannot be found anymore in Bukkit#getPlugin(String name).
     * @param manager The SimplePluginManager instance to remove the lookup name from.
     * @param name The name of the plugin to remove.
     */
    @SuppressWarnings("unchecked")
    public static void removeLookupName(Object manager, String name) {
        Map<String, Plugin> lookupNames;

        if (PAPER_INSTANCE_MANAGER != null) {
            try {
                lookupNames = (Map<String, Plugin>) LOOKUP_NAMES_FIELD.get(PAPER_INSTANCE_MANAGER);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        } else {
            lookupNames = reflection.get(manager, "lookupNames");
        }

        if (lookupNames == null) return;
        lookupNames.remove(name.replace(' ', '_'));
        lookupNames.remove(name.replace(' ', '_').toLowerCase(Locale.ENGLISH)); // Paper
    }
}
