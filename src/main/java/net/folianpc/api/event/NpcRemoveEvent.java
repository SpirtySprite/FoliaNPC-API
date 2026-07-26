package net.folianpc.api.event;

import net.folianpc.api.Npc;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

// Not cancellable: the NPC is already gone by the time this fires.
public class NpcRemoveEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Npc npc;

    public NpcRemoveEvent(Npc npc) {
        this.npc = npc;
    }

    public Npc getNpc() {
        return npc;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
