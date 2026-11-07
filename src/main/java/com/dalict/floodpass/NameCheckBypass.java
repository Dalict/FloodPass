package com.dalict.floodpass;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.UUID;

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
 *   <li>the kick message contains AuthMe's own pattern string (AuthMe includes the
 *       pattern as the {@code %reg} tag), so denies from other checks or plugins
 *       are left untouched,</li>
 *   <li>the name really does violate the pattern (sanity check).</li>
 * </ul>
 *
 * <p>The regex itself keeps applying to Java players unchanged, which is safer
 * than extending it globally: only Xbox-verified identities are exempt. This
 * mirrors what AuthMe forks with native Bedrock support patch into their
 * pre-login listener.</p>
 */
public final class NameCheckBypass implements Listener {

    private final FloodPassPlugin plugin;
    private final AuthMeNicknamePattern nicknamePattern;
    private volatile Settings settings;

    NameCheckBypass(FloodPassPlugin plugin, Settings settings, AuthMeNicknamePattern nicknamePattern) {
        this.plugin = plugin;
        this.settings = settings;
        this.nicknamePattern = nicknamePattern;
    }

    /**
     * Replaces the configuration snapshot after {@code /floodpass reload}.
     *
     * @param newSettings the newly loaded settings
     */
    void applySettings(Settings newSettings) {
        this.settings = newSettings;
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
        String pattern = nicknamePattern.pattern();
        if (pattern == null) {
            return;
        }
        if (!isFloodgateId(event.getUniqueId())) {
            return;
        }
        String name = event.getName();
        String kickMessage = event.getKickMessage();
        if (kickMessage == null || !kickMessage.contains(pattern)) {
            // Denied by something other than AuthMe's nickname check
            return;
        }
        if (matchesPattern(name, pattern)) {
            // Name actually satisfies the pattern; the deny is not ours to fix
            return;
        }

        // disallow() only assigns the result and message fields, so passing
        // ALLOWED restores admission for this connection
        event.disallow(AsyncPlayerPreLoginEvent.Result.ALLOWED, "");
        if (settings.isDebug()) {
            plugin.getLogger().info("[debug] Re-allowed Bedrock player '" + name
                    + "' denied by AuthMe nickname pattern " + pattern);
        }
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
