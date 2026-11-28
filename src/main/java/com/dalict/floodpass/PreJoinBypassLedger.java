package com.dalict.floodpass;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers Floodgate players whose pre-join dialog was skipped through
 * AuthMe's proxy auto-login path.
 *
 * <p>When AuthMe's pre-join dialog is enabled, AuthMe signs a registered
 * player in by itself before {@code PlayerJoinEvent}. FloodPass's delayed task
 * then sees an already-authenticated player, which normally means "session
 * resumed, nothing to do". This ledger tells the two cases apart so the player
 * still receives the usual auto sign-in confirmation.</p>
 *
 * <p>Entries are keyed by UUID, expire after a short time, and are consumed
 * once. A stale entry can therefore never leak a message into a later,
 * unrelated connection.</p>
 */
final class PreJoinBypassLedger {

    /** Long enough for a slow configuration phase, short enough to stay accurate. */
    private static final long TTL_MILLIS = 120_000L;

    private final Map<UUID, Entry> entries = new ConcurrentHashMap<UUID, Entry>();

    /**
     * Records that the pre-join dialog was skipped for this connection.
     *
     * @param playerId      the connecting player's UUID
     * @param wasRegistered whether AuthMe already had an account at that moment
     */
    void record(UUID playerId, boolean wasRegistered) {
        purgeExpired();
        entries.put(playerId, new Entry(wasRegistered, System.currentTimeMillis()));
    }

    /**
     * Returns and removes the record for a player.
     *
     * @param playerId the player's UUID
     * @return the record, or {@code null} when this connection was not bypassed
     */
    Entry consume(UUID playerId) {
        Entry entry = entries.remove(playerId);
        if (entry == null || entry.isExpired(System.currentTimeMillis())) {
            return null;
        }
        return entry;
    }

    /** Drops every record, for example when the plugin is disabled. */
    void clear() {
        entries.clear();
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        entries.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
    }

    /** Immutable information about one bypassed connection. */
    static final class Entry {

        private final boolean wasRegistered;
        private final long createdAt;

        Entry(boolean wasRegistered, long createdAt) {
            this.wasRegistered = wasRegistered;
            this.createdAt = createdAt;
        }

        boolean wasRegistered() {
            return wasRegistered;
        }

        boolean isExpired(long now) {
            return now - createdAt > TTL_MILLIS;
        }
    }
}
