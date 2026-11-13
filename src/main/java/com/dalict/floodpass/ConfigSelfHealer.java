package com.dalict.floodpass;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Self-healing and migration for config.yml.
 *
 * <p>Runs on plugin enable and on {@code /floodpass reload}. The disk file is
 * compared against the default config.yml bundled in the jar; the structure
 * is considered consistent only when the {@code config-version} values match
 * and every key of the default layout exists on disk with a compatible
 * type. When the structure differs (version update, admin edits broke the
 * layout), the file is regenerated from the bundled defaults and every
 * previous value that fits the new layout is migrated back into it, so
 * server owners never lose their settings across upgrades.</p>
 *
 * <p>Safety: the original file is always backed up before anything is
 * rewritten; an unreadable (corrupt) file is backed up and reset to the
 * defaults instead of throwing. Language files are deliberately not covered
 * by this mechanism.</p>
 */
final class ConfigSelfHealer {

    /** Result of a self-healing pass. */
    enum Outcome {
        /** File is absent or already matches the bundled layout. */
        INTACT,
        /** Layout was outdated or damaged; values were migrated into the new layout. */
        MIGRATED,
        /** File was unreadable and has been reset to defaults. */
        RESET
    }

    private ConfigSelfHealer() {
    }

    /**
     * Checks and, if needed, repairs config.yml.
     *
     * @param plugin the owning plugin
     * @return the outcome of the pass
     * @throws IOException only if writing the healed file fails after a
     *                     successful backup; the caller treats this as fatal
     *                     for healing but not for the plugin
     */
    static Outcome heal(JavaPlugin plugin) throws IOException {
        File file = new File(plugin.getDataFolder(), "config.yml");
        if (!file.exists()) {
            // saveDefaultConfig creates it from the bundled resource
            return Outcome.INTACT;
        }

        YamlConfiguration disk = new YamlConfiguration();
        try {
            disk.load(file);
        } catch (IOException | InvalidConfigurationException unreadable) {
            File broken = backup(plugin, file, "broken");
            if (broken == null) {
                // Never destroy anything we could not back up
                plugin.getLogger().warning("config.yml is unreadable and could not be backed up;"
                        + " the file is left untouched.");
                return Outcome.INTACT;
            }
            plugin.saveResource("config.yml", true);
            plugin.getLogger().warning("config.yml was unreadable and has been reset to defaults."
                    + " The broken original was saved as " + broken.getName());
            return Outcome.RESET;
        }

        YamlConfiguration def = loadBundled(plugin);
        if (def == null || def.getKeys(false).isEmpty()) {
            // An empty default layout would wipe the file during healing;
            // never let that happen
            plugin.getLogger().warning("Bundled default config.yml not found or empty; self-healing skipped.");
            return Outcome.INTACT;
        }

        if (structureMatches(def, disk)) {
            return Outcome.INTACT;
        }

        File backup = backup(plugin, file, "backup");
        if (backup == null) {
            plugin.getLogger().warning("config.yml differs from the expected layout but could not"
                    + " be backed up; the file is left untouched.");
            return Outcome.INTACT;
        }

        YamlConfiguration healed = new YamlConfiguration();
        for (String path : def.getKeys(true)) {
            if (def.isConfigurationSection(path)) {
                continue; // sections are created implicitly by their leaves
            }
            Object defaultValue = def.get(path);
            if (defaultValue == null) {
                // A key without a value cannot be compared or rewritten safely
                continue;
            }
            Object previousValue = disk.isSet(path) ? disk.get(path) : null;
            healed.set(path, previousValue != null && compatible(defaultValue, previousValue)
                    ? previousValue : defaultValue);
        }
        healed.save(file);
        plugin.getLogger().info("config.yml layout updated: previous values were migrated into"
                + " the new default structure (backup: " + backup.getName() + ").");
        return Outcome.MIGRATED;
    }

    /**
     * Checks whether the disk configuration carries the bundled layout:
     * matching {@code config-version} plus every default key present with a
     * compatible type.
     */
    private static boolean structureMatches(YamlConfiguration def, YamlConfiguration disk) {
        if (def.getInt("config-version", -1) != disk.getInt("config-version", -2)) {
            return false;
        }
        for (String path : def.getKeys(true)) {
            if (def.isConfigurationSection(path)) {
                continue;
            }
            Object defaultValue = def.get(path);
            if (defaultValue == null) {
                // Keys without a value cannot be compared; skip instead of
                // flagging a mismatch that could never be healed away
                continue;
            }
            if (!disk.isSet(path) || !compatible(defaultValue, disk.get(path))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Type compatibility between the default value and a value read from the
     * old file; incompatible values are replaced by defaults during healing.
     */
    private static boolean compatible(Object defaultValue, Object value) {
        if (defaultValue == null || value == null) {
            return false;
        }
        if (defaultValue instanceof Boolean) {
            return value instanceof Boolean;
        }
        if (defaultValue instanceof Number) {
            return value instanceof Number;
        }
        if (defaultValue instanceof String) {
            return value instanceof String;
        }
        if (defaultValue instanceof List) {
            return value instanceof List;
        }
        return value != null && value.getClass().equals(defaultValue.getClass());
    }

    /**
     * Copies the config to a timestamped backup.
     *
     * @return the backup file, or null when the copy failed
     */
    private static File backup(JavaPlugin plugin, File file, String kind) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        File backup = new File(plugin.getDataFolder(), "config.yml." + kind + "-" + stamp);
        try {
            Files.copy(file.toPath(), backup.toPath());
            return backup;
        } catch (IOException error) {
            plugin.getLogger().warning("Could not back up config.yml: " + error);
            return null;
        }
    }

    private static YamlConfiguration loadBundled(JavaPlugin plugin) {
        InputStream stream = plugin.getResource("config.yml");
        if (stream == null) {
            return null;
        }
        Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
        try {
            return YamlConfiguration.loadConfiguration(reader);
        } finally {
            try {
                reader.close();
            } catch (IOException ignored) {
                // Closing a resource reader is best-effort only.
            }
        }
    }
}
