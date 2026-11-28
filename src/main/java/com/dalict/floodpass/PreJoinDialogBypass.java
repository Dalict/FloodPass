package com.dalict.floodpass;

import fr.xephi.authme.api.v3.AuthMeApi;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.geysermc.floodgate.api.FloodgateApi;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Skips AuthMe's blocking pre-join dialog for verified Bedrock players.
 *
 * <p><b>Problem</b>: with {@code settings.registration.dialog.preJoin.enable: true},
 * AuthMe 6.x shows a login/register dialog during Paper's configuration phase
 * — after login, but <b>before</b> {@code PlayerJoinEvent}. While it waits on
 * that dialog, no auto-login plugin can act, so Bedrock players sit in the
 * dialog until the login timeout kicks them.</p>
 *
 * <p><b>Solution</b>: AuthMe's own public API {@code forceLoginFromProxy(String)}
 * queues a "trusted automatic login" for the name. AuthMe's configure-phase
 * handler checks that queue before showing a dialog
 * ({@code PreJoinDialogService#approvePreJoinForceLogin} and
 * {@code shouldSkipDialogs}) and proceeds without blocking. Registered players
 * are then signed in during join processing; unregistered players reach the
 * server where {@link BedrockListener} registers them as usual.</p>
 *
 * <p><b>Safety</b>: only genuine Floodgate UUIDs are queued (a Java client
 * cannot forge one), only when the connection is still allowed, and only when
 * AuthMe's pre-join dialog is actually enabled — otherwise the normal flow is
 * left untouched.</p>
 *
 * <p>The API method only exists in AuthMe 6.x; it is looked up reflectively so
 * the plugin still compiles against the 5.x API and degrades to a no-op on
 * versions without the dialog feature.</p>
 */
public final class PreJoinDialogBypass implements Listener {

    private final FloodPassPlugin plugin;
    private final AuthMePreJoinSettings authMeSettings;
    private final PreJoinBypassLedger ledger;

    /** AuthMe 6.x only; null on 5.x, which has no pre-join dialog. */
    private final Method forceLoginFromProxy;

    private final AtomicBoolean warned = new AtomicBoolean();
    private volatile Settings settings;

    PreJoinDialogBypass(FloodPassPlugin plugin, Settings settings,
                        AuthMePreJoinSettings authMeSettings, PreJoinBypassLedger ledger) {
        this.plugin = plugin;
        this.settings = settings;
        this.authMeSettings = authMeSettings;
        this.ledger = ledger;
        this.forceLoginFromProxy = resolveMethod();
    }

    private static Method resolveMethod() {
        try {
            return AuthMeApi.class.getMethod("forceLoginFromProxy", String.class);
        } catch (NoSuchMethodException notSupported) {
            return null;
        }
    }

    /** True when the running AuthMe version supports the workflow. */
    boolean isAvailable() {
        return forceLoginFromProxy != null;
    }

    /** Replaces the configuration snapshot after {@code /floodpass reload}. */
    void applySettings(Settings newSettings) {
        this.settings = newSettings;
        warned.set(false);
    }

    /**
     * Queues AuthMe's automatic login before the configuration phase starts.
     *
     * <p>MONITOR runs after every other pre-login handler, so a connection that
     * some plugin denied is not queued.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAsyncPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!settings.isBypassPreJoinDialog()) {
            return;
        }
        if (forceLoginFromProxy == null) {
            return;
        }
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        if (!isFloodgateId(event.getUniqueId())) {
            return;
        }
        if (!authMeSettings.isPreJoinDialogEnabled()) {
            // No dialog, no need to queue anything
            return;
        }
        // Read the registration state before queuing the login. AuthMe uses it
        // later on the join thread, and BedrockListener uses it to pick the
        // auto-login or auto-register confirmation message.
        Boolean wasRegistered = null;
        try {
            wasRegistered = AuthMeApi.getInstance().isRegistered(event.getName());
        } catch (Throwable error) {
            debug("Could not read registration state for " + event.getName() + ": " + error);
        }
        try {
            // forceLoginFromProxy(name) on a not-yet-joined player puts a trusted
            // auto-login request into AuthMe's proxy session queue. AuthMe sees it
            // when the configuration phase begins and skips the dialog entirely.
            forceLoginFromProxy.invoke(AuthMeApi.getInstance(), event.getName());
            if (wasRegistered != null) {
                ledger.record(event.getUniqueId(), wasRegistered);
            }
            debug("Queued AuthMe auto-login for " + event.getName()
                    + " to skip its pre-join dialog");
        } catch (Throwable error) {
            if (warned.compareAndSet(false, true)) {
                plugin.getLogger().warning("Could not bypass AuthMe's pre-join dialog for "
                        + event.getName() + ": " + error);
            }
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

    private void debug(String message) {
        if (settings.isDebug()) {
            plugin.getLogger().info("[debug] " + message);
        }
    }
}
