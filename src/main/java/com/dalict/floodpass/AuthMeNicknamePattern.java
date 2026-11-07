package com.dalict.floodpass;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * Cached view of AuthMe's {@code settings.restrictions.allowedNicknameCharacters}
 * pattern, read directly from AuthMe's config.yml.
 *
 * <p>Reading the file (instead of calling AuthMe internals) keeps this stable
 * across AuthMe versions. The file's modification time is checked on every
 * lookup, so an AuthMe reload is picked up automatically without any action
 * from FloodPass.</p>
 */
final class AuthMeNicknamePattern {

    private final JavaPlugin plugin;
    private final File authMeConfig;

    private volatile String pattern;
    private volatile long stamp = Long.MIN_VALUE;

    AuthMeNicknamePattern(JavaPlugin plugin) {
        this.plugin = plugin;
        Plugin authMe = Bukkit.getPluginManager().getPlugin("AuthMe");
        this.authMeConfig = authMe == null ? null : new File(authMe.getDataFolder(), "config.yml");
    }

    /**
     * Returns the configured nickname pattern, or null when AuthMe's config
     * cannot be read. Safe to call from asynchronous threads.
     *
     * @return the pattern such as {@code [a-zA-Z0-9_]*}, or null
     */
    String pattern() {
        File file = authMeConfig;
        if (file == null) {
            return null;
        }
        long mtime = file.lastModified();
        if (pattern != null && mtime == stamp) {
            return pattern;
        }
        try {
            String value = YamlConfiguration.loadConfiguration(file)
                    .getString("settings.restrictions.allowedNicknameCharacters", null);
            if (value != null && !value.isEmpty()) {
                pattern = value;
                stamp = mtime;
            }
        } catch (Exception error) {
            plugin.getLogger().warning("Could not read AuthMe's config.yml: " + error);
        }
        return pattern;
    }
}
