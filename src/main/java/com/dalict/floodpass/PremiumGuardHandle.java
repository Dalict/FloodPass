package com.dalict.floodpass;

/**
 * Handle to the optional PacketEvents-based premium conflict guard.
 *
 * <p>Implemented by {@link PremiumConflictGuard}. This interface deliberately
 * has no PacketEvents references, so the main plugin class can hold and call
 * it without pulling PacketEvents types into its own constant pool - otherwise
 * a server without PacketEvents would fail to load FloodPass entirely.</p>
 */
interface PremiumGuardHandle {

    /**
     * Replaces the configuration snapshot after {@code /floodpass reload}.
     *
     * @param settings the newly loaded settings
     */
    void applySettings(Settings settings);

    /**
     * Unregisters the listener from PacketEvents. Never throws, because
     * PacketEvents may already be shut down during server shutdown.
     */
    void unregister();
}
