package com.dalict.floodpass;

import fr.xephi.authme.api.v3.AuthMeApi;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.geysermc.floodgate.api.FloodgateApi;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Core listener: registers and signs in Bedrock (Floodgate) players with AuthMe.
 *
 * <p>Flow, mirroring the proven approach of AuthMe forks with native Bedrock
 * support:</p>
 * <ol>
 *   <li>Listen to {@code PlayerJoinEvent} at MONITOR priority, by which time
 *       Floodgate identity data for the connection is available.</li>
 *   <li>Run the actual work after a configurable delay, so AuthMe has finished
 *       its own join processing (limbo setup, session resume) and no race
 *       between the two plugins can occur.</li>
 *   <li>After re-checking that the player is online and still unauthenticated:
 *       registered players are force-logged-in without a password, unregistered
 *       players are registered with a random password (and signed in) when
 *       auto-registration is enabled.</li>
 * </ol>
 *
 * <p>Security basis: {@code FloodgateApi#isFloodgatePlayer} only returns true
 * for connections that passed Floodgate's encrypted handshake with a verified
 * Xbox Live token. Java Edition clients cannot forge that identity, which is
 * what makes passwordless sign-in acceptable for these players.</p>
 */
public final class BedrockListener implements Listener {

    /** Characters used for random registration passwords (no ambiguous glyphs). */
    private static final String PASSWORD_CHARS =
            "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final FloodPassPlugin plugin;
    private final Messages messages;
    private final SecureRandom random = new SecureRandom();
    private volatile Settings settings;

    BedrockListener(FloodPassPlugin plugin, Settings settings, Messages messages) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
    }

    /**
     * Replaces the configuration snapshot after {@code /floodpass reload}.
     *
     * @param newSettings the newly loaded settings
     */
    void applySettings(Settings newSettings) {
        this.settings = newSettings;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Settings config = settings;
        if (!config.isEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        // Identity is already resolvable at join time; Java players schedule nothing.
        if (!isBedrock(player.getUniqueId())) {
            return;
        }
        debug(player.getName() + " is a Floodgate player, auto sign-in in "
                + config.getDelayTicks() + " ticks");
        SchedulerCompat.runDelayed(plugin, player, () -> process(player), config.getDelayTicks());
    }

    /**
     * Performs the sign-in for a joined Bedrock player.
     *
     * @param player the online Bedrock player
     */
    private void process(Player player) {
        if (!player.isOnline()) {
            return;
        }
        Settings config = settings;

        // Fail-safe: if AuthMe or Floodgate got disabled or reloaded at runtime,
        // log the problem and leave the player to AuthMe's normal flow instead
        // of throwing inside a scheduled task.
        try {
            AuthMeApi authMe = AuthMeApi.getInstance();

            // Session resume or a prior hook may have signed the player in already.
            if (authMe.isAuthenticated(player)) {
                debug(player.getName() + " is already authenticated, skipping");
                return;
            }

            String name = player.getName();
            if (authMe.isRegistered(name)) {
                authMe.forceLogin(player);
                debug(name + " is registered, signed in without password");
                messages.send(player, "auto-login");
            } else if (config.isAutoRegister()) {
                authMe.forceRegister(player, randomPassword(config.getPasswordLength()), true);
                debug(name + " was not registered, registered with a random password and signed in");
                messages.send(player, "auto-register");
            } else {
                debug(name + " is not registered and auto-register is disabled, leaving the"
                        + " AuthMe registration flow to the player");
            }
        } catch (Throwable error) {
            plugin.getLogger().warning("Auto sign-in failed for " + player.getName()
                    + ", leaving the AuthMe flow untouched: " + error);
        }
    }

    /**
     * Checks whether the UUID belongs to a connection made through Floodgate.
     *
     * @param uuid the player UUID
     * @return true when the player connected through Floodgate
     */
    private boolean isBedrock(UUID uuid) {
        try {
            return FloodgateApi.getInstance().isFloodgatePlayer(uuid);
        } catch (Throwable error) {
            plugin.getLogger().warning("Floodgate API lookup failed for " + uuid + ": " + error);
            return false;
        }
    }

    /**
     * Generates a random password. Bedrock players never need to know it; it
     * exists only to satisfy AuthMe's hash storage.
     *
     * @param length the password length
     * @return a random alphanumeric password
     */
    private String randomPassword(int length) {
        StringBuilder password = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            password.append(PASSWORD_CHARS.charAt(random.nextInt(PASSWORD_CHARS.length())));
        }
        return password.toString();
    }

    private void debug(String message) {
        if (settings.isDebug()) {
            plugin.getLogger().info("[debug] " + message);
        }
    }
}
