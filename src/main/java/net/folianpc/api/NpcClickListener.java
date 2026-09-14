package net.folianpc.api;

import org.bukkit.entity.Player;

@FunctionalInterface
public interface NpcClickListener {
    void onClick(Player who, Npc npc, ClickType type);
}
