package com.dalict.floodpass;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * Cached view of Floodgate's {@code username-prefix} setting, read directly
 * from Floodgate's config.yml.
 *
 * <p>Reading the file keeps this independent of Floodgate internals. The
 * modification time is checked on every lookup, so a Floodgate reload is
 * picked up automatically.</p>
 */
final class FloodgatePrefixSource {

    private static final String DEFAULT_PREFIX = ".";

    private final JavaPlugin plugin;
    private final File floodgateConfig;

    private volatile String prefix;
    private volatile long stamp = Long.MIN_VALUE;

    FloodgatePrefixSource(JavaPlugin plugin) {
        this.plugin = plugin;
        Plugin floodgate = Bukkit.getPluginManager().getPlugin("floodgate");
        this.floodgateConfig = floodgate == null ? null : new File(floodgate.getDataFolder(), "config.yml");
    }

    /**
     * Returns the configured Bedrock username prefix, defaulting to ".".
     * Safe to call from asynchronous threads.
     *
     * @return the prefix such as {@code .}
     */
    String prefix() {
        File file = floodgateConfig;
        if (file == null) {
            return DEFAULT_PREFIX;
        }
        long mtime = file.lastModified();
        if (prefix != null && mtime == stamp) {
            return prefix;
        }
        try {
            String value = YamlConfiguration.loadConfiguration(file)
                    .getString("username-prefix", DEFAULT_PREFIX);
            if (value != null) {
                prefix = value;
                stamp = mtime;
            }
        } catch (Exception error) {
            plugin.getLogger().warning("Could not read Floodgate's config.yml: " + error);
        }
        return prefix == null ? DEFAULT_PREFIX : prefix;
    }
}
