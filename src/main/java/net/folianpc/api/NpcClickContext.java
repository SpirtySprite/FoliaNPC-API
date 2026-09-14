package net.folianpc.api;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;

public interface NpcClickContext {

    Player player();

    Npc npc();

    ClickType click();

    boolean sneaking();

    Plugin plugin();

    void cancelRemaining();

    boolean remainingCancelled();

    Map<String, Object> data();

    void run(Runnable task);

    void runLater(Runnable task, long delayTicks);

    void runGlobal(Runnable task);

    void runAsync(Runnable task);
}
