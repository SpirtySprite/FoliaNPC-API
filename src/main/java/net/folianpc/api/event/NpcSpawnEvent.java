package net.folianpc.api.event;

import net.folianpc.api.Npc;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

// Fires for every registered NPC, including ones another plugin created.
public class NpcSpawnEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Npc npc;

    public NpcSpawnEvent(Npc npc) {
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
