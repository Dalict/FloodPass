package com.dalict.floodpass;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * Cached view of AuthMe's pre-join dialog switch
 * ({@code settings.registration.dialog.preJoin.enable}).
 *
 * <p>AuthMe 6.x can show a blocking login/register dialog during Paper's
 * configuration phase, i.e. after login but before {@code PlayerJoinEvent}.
 * While that dialog is open no plugin can sign the player in, so FloodPass
 * must know whether it is enabled before deciding to approve it.</p>
 *
 * <p>Same pattern as {@link AuthMeNicknamePattern}: read AuthMe's file
 * directly and cache by modification time, so an AuthMe reload is picked up
 * without any action from FloodPass.</p>
 */
final class AuthMePreJoinSettings {

    private final JavaPlugin plugin;
    private final File authMeConfig;

    private volatile boolean enabled;
    private volatile boolean known;
    private volatile long stamp = Long.MIN_VALUE;

    AuthMePreJoinSettings(JavaPlugin plugin) {
        this.plugin = plugin;
        Plugin authMe = Bukkit.getPluginManager().getPlugin("AuthMe");
        this.authMeConfig = authMe == null ? null : new File(authMe.getDataFolder(), "config.yml");
    }

    /**
     * Returns whether AuthMe's pre-join dialog is enabled.
     *
     * <p>Safe to call from the Netty thread: only a file read on mtime change.</p>
     *
     * @return true when AuthMe will block players in a pre-join dialog
     */
    boolean isPreJoinDialogEnabled() {
        File file = authMeConfig;
        if (file == null) {
            return false;
        }
        long mtime = file.lastModified();
        if (known && mtime == stamp) {
            return enabled;
        }
        try {
            boolean value = YamlConfiguration.loadConfiguration(file)
                    .getBoolean("settings.registration.dialog.preJoin.enable", false);
            enabled = value;
            stamp = mtime;
            known = true;
        } catch (Exception error) {
            plugin.getLogger().warning("Could not read AuthMe's pre-join dialog setting: " + error);
        }
        return enabled;
    }
}
