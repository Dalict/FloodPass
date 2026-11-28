package com.dalict.floodpass;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiled matchers for AuthMe's nickname-check message.
 *
 * <p>AuthMe denies invalid nicknames with the text stored under
 * {@code characters_in_name} in its language files. That text is configurable:
 * owners routinely replace or translate it, and some translations drop the
 * {@code %valid_chars} placeholder entirely. Instead of expecting a fixed
 * placeholder in the kick message, this class reads the templates AuthMe
 * itself uses and matches the actual kick message against them.</p>
 *
 * <p>Sources, in order: every {@code messages_*.yml} in
 * {@code plugins/AuthMe/messages}, then the same files bundled inside the
 * AuthMe jar for languages that are not on disk. Scanning is cached and only
 * repeated when a file timestamp changes.</p>
 */
final class AuthMeNicknameMessages {

    /** AuthMe language file names; excludes {@code help_*} and backup files. */
    private static final Pattern TEMPLATE_FILE = Pattern.compile("^messages_[a-z0-9_]+\\.yml$");
    /** AuthMe placeholders such as {@code %valid_chars} or {@code %reg}. */
    private static final Pattern PLACEHOLDER = Pattern.compile("%[a-z0-9_]+");
    /** Legacy and hex color codes, in both '&' and section-sign form. */
    private static final Pattern COLOR = Pattern.compile(
            "&#[0-9a-fA-F]{6}|§x(?:§[0-9a-fA-F]){6}|[&§][0-9a-fk-orA-FK-OR]");
    private static final String TEMPLATE_KEY = "characters_in_name";
    private static final int MAX_LISTED_SOURCES = 3;

    private final JavaPlugin plugin;
    private final Plugin authMe;
    private final File messagesFolder;

    private volatile Map<String, Pattern> templates = Collections.emptyMap();
    private volatile long stamp = Long.MIN_VALUE;

    AuthMeNicknameMessages(JavaPlugin plugin) {
        this.plugin = plugin;
        Plugin authMePlugin = Bukkit.getPluginManager().getPlugin("AuthMe");
        this.authMe = authMePlugin;
        this.messagesFolder = authMePlugin == null
                ? null : new File(authMePlugin.getDataFolder(), "messages");
    }

    /**
     * Matches a kick message against the known nickname-check templates.
     *
     * @param rawKickMessage the kick message set by AuthMe
     * @return the template source that matched (for diagnostics), or null
     */
    String match(String rawKickMessage) {
        if (rawKickMessage == null || rawKickMessage.isEmpty()) {
            return null;
        }
        String plain = stripColors(rawKickMessage);
        for (Map.Entry<String, Pattern> entry : snapshot().entrySet()) {
            if (entry.getValue().matcher(plain).matches()) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Human-readable summary of the loaded templates, used in startup and
     * diagnostic logs.
     */
    String describe() {
        Map<String, Pattern> snapshot = snapshot();
        if (snapshot.isEmpty()) {
            return "0 templates (no AuthMe message file readable)";
        }
        List<String> names = new ArrayList<String>(snapshot.keySet());
        Collections.sort(names);
        StringBuilder builder = new StringBuilder();
        builder.append(names.size()).append(" template(s): ");
        for (int i = 0; i < names.size() && i < MAX_LISTED_SOURCES; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(names.get(i));
        }
        if (names.size() > MAX_LISTED_SOURCES) {
            builder.append(", ...");
        }
        return builder.toString();
    }

    /** Forces a rescan on the next lookup. */
    void invalidate() {
        stamp = Long.MIN_VALUE;
    }

    /** Warms the cache during startup and reload. */
    void preload() {
        snapshot();
    }

    /**
     * Removes legacy and hex color codes. Used for both template and kick
     * message so the comparison is independent of formatting.
     */
    static String stripColors(String input) {
        return COLOR.matcher(input).replaceAll("");
    }

    private Map<String, Pattern> snapshot() {
        long current = currentStamp();
        if (current != stamp) {
            synchronized (this) {
                if (current != stamp) {
                    templates = scan();
                    stamp = current;
                }
            }
        }
        return templates;
    }

    private Map<String, Pattern> scan() {
        Map<String, Pattern> result = new HashMap<String, Pattern>();
        scanFolder(result);
        scanJar(result);
        return result;
    }

    private void scanFolder(Map<String, Pattern> result) {
        if (messagesFolder == null || !messagesFolder.isDirectory()) {
            return;
        }
        File[] files = messagesFolder.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (!TEMPLATE_FILE.matcher(file.getName()).matches()) {
                continue;
            }
            try {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                String template = findTemplate(yaml);
                Pattern compiled = template == null ? null : compile(template);
                if (compiled != null) {
                    result.put(file.getName(), compiled);
                }
            } catch (Exception error) {
                plugin.getLogger().warning("Could not read AuthMe message file "
                        + file.getName() + ": " + error);
            }
        }
    }

    /**
     * Best-effort fallback for languages AuthMe did not extract to disk.
     * Disk files always win, so a customized file is never overridden.
     */
    private void scanJar(Map<String, Pattern> result) {
        if (authMe == null) {
            return;
        }
        try {
            URL location = authMe.getClass().getProtectionDomain().getCodeSource().getLocation();
            File jar = new File(location.toURI());
            if (!jar.isFile()) {
                return;
            }
            JarFile jarFile = new JarFile(jar);
            try {
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!name.startsWith("messages/")) {
                        continue;
                    }
                    String fileName = name.substring("messages/".length());
                    if (!TEMPLATE_FILE.matcher(fileName).matches()
                            || result.containsKey(fileName)) {
                        continue;
                    }
                    String template = readTemplate(jarFile.getInputStream(entry));
                    Pattern compiled = template == null ? null : compile(template);
                    if (compiled != null) {
                        result.put(fileName + " (jar)", compiled);
                    }
                }
            } finally {
                jarFile.close();
            }
        } catch (Throwable error) {
            // Disk files are the main source; the jar is only a fallback
            plugin.getLogger().fine("AuthMe jar message scan skipped: " + error);
        }
    }

    private String readTemplate(InputStream stream) {
        Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
        try {
            return findTemplate(YamlConfiguration.loadConfiguration(reader));
        } catch (Exception error) {
            return null;
        } finally {
            try {
                reader.close();
            } catch (java.io.IOException ignored) {
                // Closing a resource reader is best-effort only
            }
        }
    }

    /**
     * Finds {@code characters_in_name} at any nesting level, so the lookup
     * survives section renames between AuthMe versions.
     */
    private static String findTemplate(YamlConfiguration yaml) {
        for (String path : yaml.getKeys(true)) {
            if (path.equals(TEMPLATE_KEY) || path.endsWith("." + TEMPLATE_KEY)) {
                String value = yaml.getString(path);
                if (value != null && !value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * Turns a message template into an exact matcher: color codes are removed,
     * placeholders become wildcards, and all literal text is quoted so regex
     * metacharacters in the template cannot break the pattern.
     *
     * @return the matcher, or null when the template has no literal text at all
     *         (such a template would compile to {@code ^.*$} and match every
     *         kick message, including unrelated denials such as the antibot kick)
     */
    private static Pattern compile(String template) {
        String plain = stripColors(template);
        StringBuilder regex = new StringBuilder("^");
        Matcher matcher = PLACEHOLDER.matcher(plain);
        int last = 0;
        boolean hasLiteral = false;
        while (matcher.find()) {
            String literal = plain.substring(last, matcher.start());
            if (!literal.trim().isEmpty()) {
                hasLiteral = true;
            }
            regex.append(Pattern.quote(literal)).append(".*");
            last = matcher.end();
        }
        String tail = plain.substring(last);
        if (!tail.trim().isEmpty()) {
            hasLiteral = true;
        }
        if (!hasLiteral) {
            return null;
        }
        regex.append(Pattern.quote(tail)).append("$");
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    private long currentStamp() {
        if (messagesFolder == null) {
            return -1L;
        }
        long value = messagesFolder.exists() ? messagesFolder.lastModified() : -1L;
        File[] files = messagesFolder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (TEMPLATE_FILE.matcher(file.getName()).matches()) {
                    value = value * 31 + file.lastModified();
                    value = value * 31 + file.getName().hashCode();
                }
            }
        }
        return value;
    }
}
