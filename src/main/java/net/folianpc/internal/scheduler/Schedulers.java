package net.folianpc.internal.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

public final class Schedulers {

    private static volatile boolean synchronousForTesting;

    private Schedulers() {
    }

    public static void setSynchronousForTesting(boolean value) {
        synchronousForTesting = value;
    }

    public interface Handle {
        void cancel();
    }

    public static void onEntity(Plugin plugin, Entity entity, Runnable task) {
        if (synchronousForTesting) {
            task.run();
            return;
        }
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }
        try {
            entity.getScheduler().run(plugin, scheduled -> task.run(), null);
        } catch (Throwable ignored) {
        }
    }

    public static void onEntityLater(Plugin plugin, Entity entity, Runnable task, long delayTicks) {
        if (delayTicks <= 0 || synchronousForTesting) {
            onEntity(plugin, entity, task);
            return;
        }
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }
        try {
            entity.getScheduler().runDelayed(plugin, scheduled -> task.run(), null, delayTicks);
        } catch (Throwable ignored) {
        }
    }

    public static void global(Plugin plugin, Runnable task) {
        if (synchronousForTesting) {
            task.run();
            return;
        }
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }
        Bukkit.getGlobalRegionScheduler().execute(plugin, task);
    }

    // Unlike global(), this is allowed to read world/block data - Folia enforces that per-chunk.
    public static void onRegion(Plugin plugin, org.bukkit.Location location, Runnable task) {
        if (synchronousForTesting) {
            task.run();
            return;
        }
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }
        Bukkit.getRegionScheduler().execute(plugin, location, task);
    }

    public static Handle globalTimer(Plugin plugin, Runnable task, long initialDelayTicks, long periodTicks) {
        if (synchronousForTesting) {
            return () -> {
            };
        }
        long delay = Math.max(1L, initialDelayTicks);
        long period = Math.max(1L, periodTicks);
        var scheduled = Bukkit.getGlobalRegionScheduler()
                .runAtFixedRate(plugin, t -> task.run(), delay, period);
        return scheduled::cancel;
    }
}
