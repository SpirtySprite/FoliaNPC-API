package net.folianpc.api;

import org.bukkit.entity.Player;

// A one-shot click callback. For ordering, delays or conditions, use NpcAction instead.
@FunctionalInterface
public interface NpcClickListener {
    void onClick(Player who, Npc npc, ClickType type);
}
