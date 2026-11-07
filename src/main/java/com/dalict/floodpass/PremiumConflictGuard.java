package com.dalict.floodpass;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientLoginStart;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import java.util.regex.Pattern;

/**
 * Resolves the name collision between a premium-enrolled Java account and a
 * Bedrock player with the same gamertag.
 *
 * <p>AuthMe 6.x premium verification (PacketEvents-based) intercepts
 * {@code LOGIN_START} and, when the wire username matches a premium-enrolled
 * account, challenges the connection with a Mojang encryption request. A
 * Bedrock player behind Geyser carries the <b>raw</b> gamertag in that packet
 * (the Floodgate prefix is applied later, from the verified data), so a
 * Bedrock player named like a premium Java account is challenged — and can
 * never answer, since Bedrock has no Mojang session.</p>
 *
 * <p>This listener runs at {@code LOWEST} priority, before AuthMe's
 * {@code NORMAL} listener, and rewrites the wire username of verified
 * Floodgate connections to {@code prefix + username}. AuthMe's own username
 * filter ({@code ^[a-zA-Z0-9_]{2,16}$}) then rejects the prefixed name and
 * skips premium verification for the connection — exactly the built-in
 * behavior every non-colliding prefixed name already gets. Nothing else
 * changes:</p>
 *
 * <ul>
 *   <li>Floodgate consumes the LOGIN_START packet itself and builds the
 *       GameProfile from its cryptographically verified data (final name
 *       {@code prefix + gamertag}, xuid-derived UUID); the wire username is
 *       never used for the identity, so rewriting it cannot cause a double
 *       prefix</li>
 *   <li>Bedrock players without a collision are rewritten as well, but both
 *       AuthMe's non-premium path and Floodgate ignore the wire name, so the
 *       outcome is identical</li>
 *   <li>Java Edition connections are never touched: the detection attribute
 *       is only set on channels where Floodgate successfully decrypted and
 *       verified the Xbox-signed handshake data</li>
 * </ul>
 *
 * <p>Detection uses Floodgate's own channel attribute {@code floodgate-player}
 * ({@code AttributeKey.valueOf} is backed by Netty's global constant pool, so
 * the same key instance is shared). Floodgate's PacketBlocker queues the
 * LOGIN_START packet until the (asynchronous) handshake verification finished,
 * and sits before PacketEvents in the pipeline, so the attribute is always set
 * by the time this listener runs.</p>
 *
 * <p>This class is only loaded when PacketEvents is installed; without it,
 * AuthMe's premium mode never issues packet-level challenges anyway.</p>
 */
public final class PremiumConflictGuard extends PacketListenerAbstract {

    /**
     * Floodgate stores the verified FloodgatePlayer on the channel under this
     * attribute name (see Floodgate's CommonModule: AttributeKey.valueOf).
     */
    private static final AttributeKey<Object> FLOODGATE_PLAYER_KEY =
            AttributeKey.valueOf("floodgate-player");

    /**
     * AuthMe's own premium trigger filter. Usernames not matching it are
     * skipped by AuthMe's listener, so there is nothing to defuse.
     */
    private static final Pattern AUTHME_PREMIUM_TRIGGER =
            Pattern.compile("^[a-zA-Z0-9_]{2,16}$");

    private final FloodPassPlugin plugin;
    private final FloodgatePrefixSource prefixSource;
    private volatile Settings settings;

    PremiumConflictGuard(FloodPassPlugin plugin, Settings settings, FloodgatePrefixSource prefixSource) {
        super(PacketListenerPriority.LOWEST);
        this.plugin = plugin;
        this.settings = settings;
        this.prefixSource = prefixSource;
    }

    /**
     * Replaces the configuration snapshot after {@code /floodpass reload}.
     *
     * @param newSettings the newly loaded settings
     */
    void applySettings(Settings newSettings) {
        this.settings = newSettings;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Login.Client.LOGIN_START) {
            return;
        }
        if (!settings.isPremiumConflictGuard()) {
            return;
        }
        Object rawChannel = event.getUser().getChannel();
        if (!(rawChannel instanceof Channel)) {
            return;
        }
        // Only channels that passed Floodgate's encrypted Xbox verification
        if (((Channel) rawChannel).attr(FLOODGATE_PLAYER_KEY).get() == null) {
            return;
        }

        WrapperLoginClientLoginStart wrapper = new WrapperLoginClientLoginStart(event);
        String username = wrapper.getUsername();
        if (username == null || !AUTHME_PREMIUM_TRIGGER.matcher(username).matches()) {
            // AuthMe would skip this name anyway
            return;
        }

        String prefixed = prefixSource.prefix() + username;
        wrapper.setUsername(prefixed);
        event.markForReEncode(true);
        if (settings.isDebug()) {
            plugin.getLogger().info("[debug] Rewrote LOGIN_START username of Bedrock player '"
                    + username + "' to '" + prefixed + "' to skip AuthMe premium verification");
        }
    }
}
