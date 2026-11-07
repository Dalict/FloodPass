package com.dalict.floodpass;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Player-facing messages loaded from a standalone language file in the
 * {@code lang/} subfolder of the plugin directory
 * ({@code lang/messages_<lang>.yml}).
 *
 * <p>Language resolution is deliberately not limited to the bundled languages.
 * A {@code language} value such as {@code zh_tw} works as soon as a matching
 * {@code lang/messages_zh_tw.yml} exists on disk, no matter who created it.
 * The lookup order per language code is:</p>
 * <ol>
 *   <li>an existing file in {@code plugins/FloodPass/lang/} (any language),</li>
 *   <li>the bundled resource of that language, extracted to the folder,</li>
 *   <li>English as fallback, with a console warning.</li>
 * </ol>
 *
 * <p>Keys missing from a custom file fall back to the bundled file of the
 * same language, then to bundled English. Console and log output is
 * intentionally not translated and stays in English.</p>
 */
public final class Messages {

    /** Language used when the configuration value is empty or unresolvable. */
    public static final String DEFAULT_LANGUAGE = "en";

    /** Subfolder of the plugin data folder holding all language files. */
    private static final String LANG_FOLDER = "lang";

    /**
     * Languages shipped inside the jar. This list only controls which sample
     * files are extracted as templates; it never restricts what
     * {@code language} may reference.
     */
    private static final String[] BUNDLED_LANGUAGES = {"en", "zh_cn"};

    /** All keys the plugin reads from a language file. */
    private static final String[] KNOWN_KEYS = {
            "auto-login",
            "auto-register",
            "command-reload-done",
            "command-status-header",
            "command-status-state",
            "command-status-delay",
            "command-status-auto-register",
            "command-status-usage",
            "command-status-enabled",
            "command-status-disabled"
    };

    private final JavaPlugin plugin;
    private final Map<String, String> entries = new HashMap<String, String>();
    private String loadedLanguage = DEFAULT_LANGUAGE;

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Loads the requested language file, accepting any language for which a
     * file can be resolved (disk file first, bundled resource second).
     *
     * @param language the language code from config.yml (for example {@code en},
     *                 {@code zh_cn}, {@code zh_tw})
     */
    public void load(String language) {
        String lang = normalize(language);

        File file = fileFor(lang);
        if (!file.exists()) {
            if (hasBundled(lang)) {
                // First use of a bundled language: extract it to the folder.
                extract(lang);
            } else if (!DEFAULT_LANGUAGE.equals(lang)) {
                plugin.getLogger().warning("Language '" + lang + "' has no file in " + LANG_FOLDER
                        + "/ and is not bundled, falling back to '" + DEFAULT_LANGUAGE + "'.");
                lang = DEFAULT_LANGUAGE;
            }
            file = fileFor(lang);
            if (!file.exists() && hasBundled(lang)) {
                extract(lang);
            }
        }

        // Parse each source once: the disk file, the bundled file of the same
        // language, and (for custom languages) bundled English as last resort.
        YamlConfiguration disk = file.exists()
                ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        YamlConfiguration sameLanguage = readBundled(lang);
        YamlConfiguration english = DEFAULT_LANGUAGE.equals(lang) ? null : readBundled(DEFAULT_LANGUAGE);

        entries.clear();
        for (String key : KNOWN_KEYS) {
            String value = disk.getString(key);
            if (value == null && sameLanguage != null) {
                value = sameLanguage.getString(key);
            }
            if (value == null && english != null) {
                value = english.getString(key);
            }
            // A key present on disk but empty means "message disabled" and is
            // respected; genuinely missing keys fall back above.
            entries.put(key, value == null ? "" : value);
        }
        loadedLanguage = lang;
        plugin.getLogger().info("Loaded messages from " + LANG_FOLDER + "/" + fileNameFor(lang) + ".");
    }

    /**
     * Extracts every bundled language file into the {@code lang/} folder so
     * administrators can switch languages or use them as templates without
     * digging into the jar. Existing files are never overwritten.
     */
    public void extractBundledLanguages() {
        for (String lang : BUNDLED_LANGUAGES) {
            File file = fileFor(lang);
            if (!file.exists() && hasBundled(lang)) {
                extract(lang);
            }
        }
    }

    public String getLanguage() {
        return loadedLanguage;
    }

    /**
     * Sends the message stored under the given key to a sender, colorized.
     * Does nothing when the key is absent or set to an empty string.
     *
     * @param sender       the recipient
     * @param key          the message key
     * @param replacements optional {@code {placeholder}, value} pairs
     */
    public void send(CommandSender sender, String key, String... replacements) {
        String message = raw(key, replacements);
        if (message == null || message.isEmpty()) {
            return;
        }
        sender.sendMessage(message);
    }

    /**
     * Builds the colored message for a key. Use the result for values that get
     * embedded into other messages.
     *
     * @return the formatted message, or {@code null} when the key is missing or empty
     */
    public String raw(String key, String... replacements) {
        String value = entries.get(key);
        if (value == null || value.isEmpty()) {
            return null;
        }
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            value = value.replace(replacements[i], replacements[i + 1]);
        }
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    /**
     * Parses the bundled language file of the given language.
     *
     * @return the parsed configuration, or null when the jar has no such file
     */
    private YamlConfiguration readBundled(String lang) {
        InputStream stream = plugin.getResource(resourcePathFor(lang));
        if (stream == null) {
            return null;
        }
        Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
        try {
            return YamlConfiguration.loadConfiguration(reader);
        } finally {
            try {
                reader.close();
            } catch (java.io.IOException ignored) {
                // Closing a resource reader is best-effort only.
            }
        }
    }

    private boolean hasBundled(String lang) {
        return plugin.getResource(resourcePathFor(lang)) != null;
    }

    private void extract(String lang) {
        File folder = new File(plugin.getDataFolder(), LANG_FOLDER);
        if (!folder.exists()) {
            folder.mkdirs();
        }
        plugin.saveResource(resourcePathFor(lang), false);
    }

    private static String normalize(String language) {
        if (language == null || language.trim().isEmpty()) {
            return DEFAULT_LANGUAGE;
        }
        return language.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static String fileNameFor(String lang) {
        return "messages_" + lang + ".yml";
    }

    private static String resourcePathFor(String lang) {
        return LANG_FOLDER + "/" + fileNameFor(lang);
    }

    private File fileFor(String lang) {
        return new File(new File(plugin.getDataFolder(), LANG_FOLDER), fileNameFor(lang));
    }
}
