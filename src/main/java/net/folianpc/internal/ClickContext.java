package net.folianpc.internal;

import net.folianpc.api.ClickType;
import net.folianpc.api.Npc;
import net.folianpc.api.NpcClickContext;
import net.folianpc.internal.scheduler.Schedulers;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// One per click, shared by every action it triggers. Delayed actions can run on a later tick, so
// cancelled is volatile.
public final class ClickContext implements NpcClickContext {

    private final Plugin plugin;
    private final Player player;
    private final Npc npc;
    private final ClickType click;
    private final boolean sneaking;
    private final java.util.concurrent.Executor async;
    private final Map<String, Object> data = new ConcurrentHashMap<>();
    private volatile boolean cancelled;

    public ClickContext(Plugin plugin, Player player, Npc npc, ClickType click, boolean sneaking,
                        java.util.concurrent.Executor async) {
        this.plugin = plugin;
        this.player = player;
        this.npc = npc;
        this.click = click;
        this.sneaking = sneaking;
        this.async = async;
    }

    @Override
    public Player player() {
        return player;
    }

    @Override
    public Npc npc() {
        return npc;
    }

    @Override
    public ClickType click() {
        return click;
    }

    @Override
    public boolean sneaking() {
        return sneaking;
    }

    @Override
    public Plugin plugin() {
        return plugin;
    }

    @Override
    public void cancelRemaining() {
        this.cancelled = true;
    }

    @Override
    public boolean remainingCancelled() {
        return cancelled;
    }

    @Override
    public Map<String, Object> data() {
        return data;
    }

    @Override
    public void run(Runnable task) {
        Schedulers.onEntity(plugin, player, task);
    }

    @Override
    public void runLater(Runnable task, long delayTicks) {
        Schedulers.onEntityLater(plugin, player, task, delayTicks);
    }

    @Override
    public void runGlobal(Runnable task) {
        Schedulers.global(plugin, task);
    }

    @Override
    public void runAsync(Runnable task) {
        async.execute(task);
    }
}
