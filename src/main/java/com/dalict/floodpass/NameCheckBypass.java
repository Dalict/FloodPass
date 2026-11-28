package com.dalict.floodpass;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Re-allows Bedrock players that AuthMe rejected for their nickname pattern.
 *
 * <p>AuthMe denies names failing {@code settings.restrictions.allowedNicknameCharacters}
 * (default {@code [a-zA-Z0-9_]*}) during {@code AsyncPlayerPreLoginEvent} at LOWEST
 * priority — which rejects every Floodgate player, since their name carries the
 * configured prefix (for example {@code .Steve}). Without help, such players can
 * never join, regardless of any auto-login plugin.</p>
 *
 * <p>FloodPass re-examines the deny at LOWEST priority (after AuthMe's LOWEST
 * deny, which is guaranteed by registration order: same-priority handlers run
 * in registration order and the hard dependency ensures AuthMe registers
 * first) and restores admission only when <b>all</b> of the following hold:</p>
 * <ul>
 *   <li>the deny result is {@code KICK_OTHER} (the result AuthMe uses for this check),</li>
 *   <li>the connection's UUID is a genuine Floodgate id (xuid-derived; a Java
 *       Edition client cannot forge one),</li>
 *   <li>the name really violates the configured pattern (sanity check),</li>
 *   <li>the kick message matches AuthMe's own {@code characters_in_name}
 *       template, or (fallback) contains the pattern string itself. This keeps
 *       denies from other checks untouched — most importantly the antibot
 *       kick, which also uses {@code KICK_OTHER}.</li>
 * </ul>
 *
 * <p>Matching AuthMe's template rather than a fixed placeholder is what makes
 * custom, translated and fork-shipped language files work: a message that
 * dropped {@code %valid_chars} is still recognized. When the deny cannot be
 * identified, a one-time console warning shows the actual kick message and how
 * to fix it, instead of failing silently.</p>
 *
 * <p>The regex itself keeps applying to Java players unchanged, which is safer
 * than extending it globally: only Xbox-verified identities are exempt. This
 * mirrors what AuthMe forks with native Bedrock support patch into their
 * pre-login listener.</p>
 */
public final class NameCheckBypass implements Listener {

    /** Longest kick message written to the console warning. */
    private static final int MAX_LOGGED_MESSAGE = 120;

    private final FloodPassPlugin plugin;
    private final AuthMeNicknamePattern nicknamePattern;
    private final AuthMeNicknameMessages nicknameMessages;
    private final AtomicBoolean warned = new AtomicBoolean();
    private volatile Settings settings;

    NameCheckBypass(FloodPassPlugin plugin, Settings settings,
                    AuthMeNicknamePattern nicknamePattern,
                    AuthMeNicknameMessages nicknameMessages) {
        this.plugin = plugin;
        this.settings = settings;
        this.nicknamePattern = nicknamePattern;
        this.nicknameMessages = nicknameMessages;
    }

    /**
     * Replaces the configuration snapshot after {@code /floodpass reload}.
     *
     * @param newSettings the newly loaded settings
     */
    void applySettings(Settings newSettings) {
        this.settings = newSettings;
        // Allow the diagnostic warning to be emitted again after a reload
        warned.set(false);
    }

    // LOWEST, registered after AuthMe (hard dependency): Bukkit runs
    // same-priority handlers in registration order, so this executes right
    // after AuthMe's deny and before LOW listeners such as LuckPerms.
    // Restoring the ALLOWED result that early is essential - LuckPerms's LOW
    // handler skips loading permission data for denied connections and its
    // MONITOR handler kills re-allowed connections with an empty kick.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!settings.isBypassNicknameCheck()) {
            return;
        }
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.KICK_OTHER) {
            return;
        }
        if (!isFloodgateId(event.getUniqueId())) {
            return;
        }
        String pattern = nicknamePattern.pattern();
        if (pattern == null) {
            // Reported once at startup; nothing to compare against
            return;
        }
        String name = event.getName();
        if (matchesPattern(name, pattern)) {
            // The name satisfies the pattern; this deny is not ours to fix
            return;
        }

        String kickMessage = event.getKickMessage();
        String matchedTemplate = nicknameMessages.match(kickMessage);
        boolean matched = matchedTemplate != null
                || (kickMessage != null && kickMessage.contains(pattern));
        if (!matched) {
            // A Bedrock player with a pattern-violating name was denied, but the
            // message is neither AuthMe's nickname check nor its template
            warnUnmatched(name, kickMessage);
            return;
        }

        // disallow() only assigns the result and message fields, so passing
        // ALLOWED restores admission for this connection
        event.disallow(AsyncPlayerPreLoginEvent.Result.ALLOWED, "");
        if (settings.isDebug()) {
            plugin.getLogger().info("[debug] Re-allowed Bedrock player '" + name
                    + "' (pattern " + pattern + ", matched "
                    + (matchedTemplate != null ? matchedTemplate : "pattern in kick message") + ")");
        }
    }

    private void warnUnmatched(String name, String kickMessage) {
        if (settings.isDebug()) {
            plugin.getLogger().info("[debug] Bedrock player '" + name + "' was denied and the"
                    + " message could not be identified: "
                    + (kickMessage == null ? "(no message)" : kickMessage));
        }
        if (!warned.compareAndSet(false, true)) {
            return;
        }
        String shown = kickMessage == null
                ? "(none)" : truncate(AuthMeNicknameMessages.stripColors(kickMessage));
        plugin.getLogger().warning("A Bedrock player was denied with a message FloodPass could"
                + " not identify, so the nickname bypass stayed inactive.");
        plugin.getLogger().warning("  Kick message: \"" + shown + "\"");
        plugin.getLogger().warning("  Templates   : " + nicknameMessages.describe());
        plugin.getLogger().warning("  If this is AuthMe's nickname-character check, restore the"
                + " %valid_chars placeholder in that language file, or use the official one."
                + " Java players are unaffected.");
    }

    private static String truncate(String value) {
        if (value.length() <= MAX_LOGGED_MESSAGE) {
            return value;
        }
        return value.substring(0, MAX_LOGGED_MESSAGE) + "...";
    }

    private boolean matchesPattern(String name, String pattern) {
        try {
            return name.matches(pattern);
        } catch (java.util.regex.PatternSyntaxException invalidPattern) {
            // Mirror AuthMe's safePatternCompile fallback: treat as no match
            return false;
        }
    }

    private boolean isFloodgateId(UUID uuid) {
        try {
            return FloodgateApi.getInstance().isFloodgateId(uuid);
        } catch (Throwable error) {
            plugin.getLogger().warning("Floodgate API lookup failed for " + uuid + ": " + error);
            return false;
        }
    }
}
