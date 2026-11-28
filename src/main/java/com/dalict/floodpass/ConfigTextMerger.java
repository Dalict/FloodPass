package com.dalict.floodpass;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Merges migrated values into the bundled config text without losing comments.
 *
 * <p>Bukkit's {@code YamlConfiguration#save} rewrites the file from the parsed
 * object model, which drops every comment. For a config that is meant to be
 * read and edited by server owners that is unacceptable, so the self-healer
 * uses this text-based merger instead: the bundled default file (with all its
 * comments) is treated as a template, and only the scalar value after each
 * known key is replaced.</p>
 *
 * <p>The parser understands the shape this plugin actually ships: comments,
 * blank lines, nested sections (indentation) and scalar values. Lists and
 * block scalars are left untouched; the config currently has none, and such
 * values are deliberately not migrated rather than serialized incorrectly.</p>
 */
final class ConfigTextMerger {

    /** Matches "indent + key: value" lines. Values may be absent (section header). */
    private static final Pattern KEY_LINE =
            Pattern.compile("^(\\s*)([A-Za-z0-9_.-]+):(?:[ \\t]+(.*))?$");

    /** Strings matching this can be written without quotes. */
    private static final Pattern PLAIN_SAFE = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_.-]*$");

    /** YAML keywords that must be quoted when used as a string value. */
    private static final List<String> YAML_KEYWORDS = Arrays.asList(
            "true", "false", "yes", "no", "on", "off", "null", "~");

    private ConfigTextMerger() {
    }

    /**
     * Writes {@code values} into {@code defaultText}, keeping every comment,
     * blank line and section header of the template.
     *
     * @param defaultText the bundled config.yml text (with comments)
     * @param values      key path (e.g. {@code auto-register.enabled}) to value
     * @return the merged text, ending with exactly one newline
     */
    static String merge(String defaultText, Map<String, Object> values) {
        String normalized = defaultText.replace("\r\n", "\n").replace('\r', '\n');
        while (normalized.endsWith("\n")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String[] lines = normalized.split("\n", -1);

        List<String> output = new ArrayList<String>(lines.length + 8);
        List<String> pathStack = new ArrayList<String>();
        List<Integer> indentStack = new ArrayList<Integer>();

        for (String line : lines) {
            String trimmed = line.trim();
            Matcher matcher = KEY_LINE.matcher(line);
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !matcher.matches()) {
                output.add(line);
                continue;
            }

            String indentText = matcher.group(1);
            String key = matcher.group(2);
            String rawValue = matcher.group(3);
            int indent = indentText.length();

            // Leave nested sections when dedenting back to an outer level
            while (!indentStack.isEmpty()
                    && indentStack.get(indentStack.size() - 1) >= indent) {
                indentStack.remove(indentStack.size() - 1);
                pathStack.remove(pathStack.size() - 1);
            }

            String path = pathStack.isEmpty() ? key : joinPath(pathStack, key);

            if (rawValue == null || rawValue.trim().isEmpty()
                    || rawValue.trim().startsWith("#")) {
                // Section header (possibly with an inline comment): remember it
                // for nested keys, keep the line as is
                indentStack.add(indent);
                pathStack.add(key);
                output.add(line);
                continue;
            }

            Object value = values.get(path);
            if (value == null) {
                output.add(line);
                continue;
            }
            output.add(indentText + key + ": " + formatValue(value));
        }

        StringBuilder builder = new StringBuilder(normalized.length() + 256);
        for (int i = 0; i < output.size(); i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(output.get(i));
        }
        builder.append('\n');
        return builder.toString();
    }

    private static String joinPath(List<String> pathStack, String key) {
        StringBuilder builder = new StringBuilder();
        for (String part : pathStack) {
            builder.append(part).append('.');
        }
        return builder.append(key).toString();
    }

    /**
     * Serializes a scalar for YAML. Strings are quoted only when necessary.
     */
    private static String formatValue(Object value) {
        if (value instanceof Boolean || value instanceof Number) {
            return String.valueOf(value);
        }
        String text = String.valueOf(value);
        if (text.isEmpty()) {
            return "''";
        }
        if (PLAIN_SAFE.matcher(text).matches()
                && !YAML_KEYWORDS.contains(text.toLowerCase(Locale.ROOT))) {
            return text;
        }
        return "'" + text.replace("'", "''") + "'";
    }
}
