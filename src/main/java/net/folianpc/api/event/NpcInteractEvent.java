package net.folianpc.api.event;

import net.folianpc.api.ClickType;
import net.folianpc.api.Npc;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

// Fires before the NPC's own listener/actions; cancelling stops both. Fired on the clicking
// player's region thread.
public class NpcInteractEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Npc npc;
    private final ClickType click;
    private final boolean sneaking;
    private boolean cancelled;

    public NpcInteractEvent(Player player, Npc npc, ClickType click, boolean sneaking) {
        this.player = player;
        this.npc = npc;
        this.click = click;
        this.sneaking = sneaking;
    }

    public Player getPlayer() {
        return player;
    }

    public Npc getNpc() {
        return npc;
    }

    public ClickType getClick() {
        return click;
    }

    public boolean isSneaking() {
        return sneaking;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
