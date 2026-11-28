package com.dalict.floodpass;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * Schedules a task relative to a player on any server flavour.
 *
 * <p>On Paper 1.20.1+ and Folia the per-entity scheduler is used, which is
 * required on Folia (the global Bukkit scheduler throws there) and always
 * region-safe. The scheduler is reached through reflection so the plugin stays
 * compiled against plain Bukkit APIs and keeps Java 8 bytecode. On Spigot and
 * older servers, where {@code Player#getScheduler()} does not exist, the task
 * falls back to the global main-thread scheduler.</p>
 */
final class SchedulerCompat {

    private SchedulerCompat() {
    }

    /**
     * Runs the task for the given player after the specified delay.
     *
     * @param plugin     the owning plugin
     * @param player     the player the task belongs to
     * @param task       the action to run
     * @param delayTicks the delay in ticks, at least 1
     */
    static void runDelayed(JavaPlugin plugin, Player player, Runnable task, long delayTicks) {
        long delay = Math.max(1, delayTicks);
        if (runViaEntityScheduler(plugin, player, task, delay)) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, task, delay);
    }

    /**
     * Best-effort scheduling through Paper/Folia's entity scheduler.
     *
     * @return true when the task was scheduled through the entity scheduler
     */
    private static boolean runViaEntityScheduler(JavaPlugin plugin, Player player,
                                                 Runnable task, long delay) {
        try {
            Object scheduler = player.getClass().getMethod("getScheduler").invoke(player);
            Method runDelayed = scheduler.getClass()
                    .getMethod("runDelayed", Plugin.class, Consumer.class, Runnable.class, long.class);
            runDelayed.invoke(scheduler, plugin, (Consumer<?>) scheduledTask -> task.run(), null, delay);
            return true;
        } catch (NoSuchMethodException notPaperOrFolia) {
            // Expected on Spigot and Paper builds without the regionized scheduler.
            return false;
        } catch (IllegalAccessException | InvocationTargetException e) {
            plugin.getLogger().warning("Entity scheduler rejected a task, falling back to the"
                    + " global scheduler: " + e);
            return false;
        }
    }
}
