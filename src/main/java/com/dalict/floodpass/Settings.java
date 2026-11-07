package com.dalict.floodpass;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Immutable snapshot of config.yml.
 *
 * <p>Rebuilt as a whole on reload; the listener swaps its reference atomically
 * so a reload never leaves the plugin holding a half-applied configuration.</p>
 */
public final class Settings {

    /** Lower bound for the sign-in delay. The entity scheduler requires at least 1 tick. */
    private static final int MIN_DELAY_TICKS = 1;
    /** Upper bound for the sign-in delay (one full minute). */
    private static final int MAX_DELAY_TICKS = 20 * 60;
    /** Bounds for the random registration password length. */
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 64;

    private final String language;
    private final boolean enabled;
    private final int delayTicks;
    private final boolean autoRegister;
    private final int passwordLength;
    private final boolean bypassNicknameCheck;
    private final boolean premiumConflictGuard;
    private final boolean debug;

    private Settings(String language, boolean enabled, int delayTicks, boolean autoRegister,
                     int passwordLength, boolean bypassNicknameCheck, boolean premiumConflictGuard,
                     boolean debug) {
        this.language = language;
        this.enabled = enabled;
        this.delayTicks = delayTicks;
        this.autoRegister = autoRegister;
        this.passwordLength = passwordLength;
        this.bypassNicknameCheck = bypassNicknameCheck;
        this.premiumConflictGuard = premiumConflictGuard;
        this.debug = debug;
    }

    /**
     * Reads and validates the plugin configuration.
     *
     * @param config the loaded config.yml
     * @return a validated settings snapshot
     */
    public static Settings load(FileConfiguration config) {
        String language = config.getString("language", Messages.DEFAULT_LANGUAGE);

        int delay = clamp(config.getInt("login-delay-ticks", 20), MIN_DELAY_TICKS, MAX_DELAY_TICKS);
        int length = clamp(config.getInt("auto-register.password-length", 32),
                MIN_PASSWORD_LENGTH, MAX_PASSWORD_LENGTH);

        return new Settings(
                language,
                config.getBoolean("enabled", true),
                delay,
                config.getBoolean("auto-register.enabled", true),
                length,
                config.getBoolean("bypass-nickname-check", true),
                config.getBoolean("premium-conflict-guard", true),
                config.getBoolean("debug", false));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public String getLanguage() {
        return language;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getDelayTicks() {
        return delayTicks;
    }

    public boolean isAutoRegister() {
        return autoRegister;
    }

    public int getPasswordLength() {
        return passwordLength;
    }

    public boolean isBypassNicknameCheck() {
        return bypassNicknameCheck;
    }

    public boolean isPremiumConflictGuard() {
        return premiumConflictGuard;
    }

    public boolean isDebug() {
        return debug;
    }
}
