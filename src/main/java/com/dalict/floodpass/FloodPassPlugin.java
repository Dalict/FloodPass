package com.dalict.floodpass;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/**
 * FloodPass main class.
 *
 * <p>FloodPass gives Floodgate (Bedrock) players passwordless access to an
 * AuthMe-protected server: players verified through Xbox Live are signed in
 * automatically, and first-time Bedrock players are optionally registered with
 * a random password, so no Bedrock player ever has to type a password.</p>
 */
public final class FloodPassPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {

    private BedrockListener listener;
    private NameCheckBypass nameCheckBypass;
    private PremiumGuardHandle premiumConflictGuard;

    /** 功能4：跳过 AuthMe 的 preJoin 阻塞对话框（仅基岩玩家） */
    private PreJoinDialogBypass preJoinDialogBypass;
    private Messages messages;
    private AuthMeNicknamePattern nicknamePattern;
    private AuthMeNicknameMessages nicknameMessages;
    private volatile Settings settings;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        selfHealConfiguration();

        // Hard dependencies are already guaranteed by plugin.yml; this is only
        // a defensive check with a readable message.
        String missing = missingDependency();
        if (missing != null) {
            getLogger().severe("Required dependency " + missing + " is missing, disabling."
                    + " FloodPass needs both Floodgate and AuthMe.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        settings = Settings.load(getConfig());
        messages = new Messages(this);
        messages.extractBundledLanguages();
        messages.load(settings.getLanguage());

        listener = new BedrockListener(this, settings, messages);
        getServer().getPluginManager().registerEvents(listener, this);

        nicknamePattern = new AuthMeNicknamePattern(this);
        nicknameMessages = new AuthMeNicknameMessages(this);
        if (settings.isBypassNicknameCheck()) {
            nicknameMessages.preload();
        }
        nameCheckBypass = new NameCheckBypass(this, settings, nicknamePattern, nicknameMessages);
        getServer().getPluginManager().registerEvents(nameCheckBypass, this);

        registerPremiumConflictGuard();
        registerPreJoinDialogBypass();

        Plugin authMe = getServer().getPluginManager().getPlugin("AuthMe");
        Plugin floodgate = getServer().getPluginManager().getPlugin("floodgate");
        getLogger().info("Enabled on server " + getServer().getName() + " "
                + getServer().getVersion());
        getLogger().info("Hooked into AuthMe "
                + (authMe != null ? authMe.getDescription().getVersion() : "?")
                + " and Floodgate "
                + (floodgate != null ? floodgate.getDescription().getVersion() : "?")
                + (settings.isEnabled() ? ", auto sign-in enabled" : ", auto sign-in disabled by config"));
        if (settings.isBypassNicknameCheck()) {
            String pattern = nicknamePattern.pattern();
            if (pattern != null) {
                getLogger().info("Nickname check bypass active for Floodgate players"
                        + " (AuthMe pattern: " + pattern + "; "
                        + nicknameMessages.describe() + ")");
            } else {
                getLogger().warning("Nickname check bypass enabled, but AuthMe's"
                        + " allowedNicknameCharacters cannot be read from"
                        + " plugins/AuthMe/config.yml - the bypass stays inactive.");
            }
        }
    }

    @Override
    public void onDisable() {
        // PacketEvents listeners are not tied to the Bukkit plugin lifecycle;
        // unregister explicitly so a disabled FloodPass stops rewriting packets.
        // The call goes through the handle interface, which keeps PacketEvents
        // types out of this class.
        PremiumGuardHandle guard = premiumConflictGuard;
        premiumConflictGuard = null;
        if (guard != null) {
            guard.unregister();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            // Re-extract a deleted config.yml and any deleted bundled language
            // file; both calls never overwrite existing files.
            saveDefaultConfig();
            selfHealConfiguration();
            messages.extractBundledLanguages();
            reloadConfig();
            settings = Settings.load(getConfig());
            if (listener != null) {
                listener.applySettings(settings);
            }
            if (nameCheckBypass != null) {
                nameCheckBypass.applySettings(settings);
            }
            if (preJoinDialogBypass != null) {
                preJoinDialogBypass.applySettings(settings);
            } else if (settings.isBypassPreJoinDialog()) {
                // Enabling the bypass via reload must work without a restart
                registerPreJoinDialogBypass();
            }
            if (nicknameMessages != null) {
                // Pick up edits to AuthMe's language files without a restart
                nicknameMessages.invalidate();
                nicknameMessages.preload();
            }
            if (premiumConflictGuard != null) {
                premiumConflictGuard.applySettings(settings);
            } else if (settings.isPremiumConflictGuard()) {
                // Enabling the guard via reload must work without a restart
                registerPremiumConflictGuard();
            }
            messages.load(settings.getLanguage());
            messages.send(sender, "command-reload-done");
            return true;
        }

        Settings config = settings;
        if (config != null) {
            String state = stateName(config.isEnabled());
            String registerState = stateName(config.isAutoRegister());

            messages.send(sender, "command-status-header",
                    "{version}", getDescription().getVersion());
            messages.send(sender, "command-status-state", "{state}", state);
            messages.send(sender, "command-status-delay",
                    "{ticks}", String.valueOf(config.getDelayTicks()));
            messages.send(sender, "command-status-auto-register", "{state}", registerState);
            messages.send(sender, "command-status-usage", "{label}", label);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && "reload".startsWith(args[0].toLowerCase())) {
            return Collections.singletonList("reload");
        }
        return Collections.emptyList();
    }

    private String missingDependency() {
        if (getServer().getPluginManager().getPlugin("floodgate") == null) {
            return "Floodgate";
        }
        if (getServer().getPluginManager().getPlugin("AuthMe") == null) {
            return "AuthMe";
        }
        return null;
    }

    /**
     * Registers the premium name-collision guard when PacketEvents is present.
     *
     * <p>The guard extends a PacketEvents class, so it is loaded reflectively:
     * a direct type reference would put PacketEvents classes into this class's
     * constant pool and make FloodPass fail to load on servers without
     * PacketEvents. Without PacketEvents, AuthMe's premium mode never issues
     * packet-level challenges anyway, so the guard is simply skipped.</p>
     */
    private void registerPremiumConflictGuard() {
        if (!settings.isPremiumConflictGuard()) {
            return;
        }
        if (getServer().getPluginManager().getPlugin("packetevents") == null) {
            getLogger().info("Premium conflict guard idle: PacketEvents not installed."
                    + " Only needed together with AuthMe's premium verification (enablePremium).");
            return;
        }
        try {
            Class<?> guardClass = Class.forName(
                    "com.dalict.floodpass.PremiumConflictGuard", true, getClass().getClassLoader());
            Method register = guardClass.getMethod("register",
                    FloodPassPlugin.class, Settings.class, FloodgatePrefixSource.class);
            premiumConflictGuard = (PremiumGuardHandle) register.invoke(
                    null, this, settings, new FloodgatePrefixSource(this));
            getLogger().info("Premium conflict guard active: Bedrock players sharing a name with a"
                    + " premium-enrolled Java account can join (prefix rewrite at LOWEST priority).");
        } catch (Throwable error) {
            getLogger().warning("Failed to register the premium conflict guard: " + error);
        }
    }

    /**
     * Registers the pre-join dialog bypass.
     *
     * <p>AuthMe 6.x can block players in a login/register dialog during the
     * configuration phase, before {@code PlayerJoinEvent}. While that dialog is
     * open no plugin can sign the player in. The bypass uses AuthMe's own
     * public {@code forceLoginFromProxy} API to queue a trusted auto-login for
     * verified Floodgate players, which makes AuthMe skip the dialog. AuthMe's
     * config is not modified.</p>
     */
    private void registerPreJoinDialogBypass() {
        if (!settings.isBypassPreJoinDialog()) {
            return;
        }
        AuthMePreJoinSettings authMePreJoin = new AuthMePreJoinSettings(this);
        PreJoinDialogBypass bypass = new PreJoinDialogBypass(this, settings, authMePreJoin);
        if (!bypass.isAvailable()) {
            if (authMePreJoin.isPreJoinDialogEnabled()) {
                getLogger().warning("AuthMe's pre-join dialog is enabled, but this AuthMe version"
                        + " has no forceLoginFromProxy API. Set"
                        + " settings.registration.dialog.preJoin.enable to false in AuthMe's"
                        + " config.yml, or Bedrock players will time out in that dialog.");
            }
            return;
        }
        getServer().getPluginManager().registerEvents(bypass, this);
        preJoinDialogBypass = bypass;
        if (authMePreJoin.isPreJoinDialogEnabled()) {
            getLogger().info("Pre-join dialog bypass active: AuthMe's pre-join dialog is skipped"
                    + " for Floodgate players (no AuthMe config change needed).");
        } else {
            getLogger().info("Pre-join dialog bypass idle: AuthMe's pre-join dialog is disabled.");
        }
    }

    /**
     * Runs the config self-healing pass; never throws, so a broken or exotic
     * configuration can never prevent the plugin (or the server) from starting.
     */
    private void selfHealConfiguration() {
        try {
            ConfigSelfHealer.heal(this);
        } catch (Throwable error) {
            getLogger().warning("Config self-healing skipped due to an unexpected error: " + error);
        }
    }

    /** Resolves the localized on/off label used in the status output. */
    private String stateName(boolean on) {
        String value = messages.raw(on ? "command-status-enabled" : "command-status-disabled");
        return value == null ? (on ? "on" : "off") : value;
    }
}
