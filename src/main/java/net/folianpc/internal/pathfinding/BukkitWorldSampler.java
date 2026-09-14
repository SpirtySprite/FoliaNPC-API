package net.folianpc.internal.pathfinding;

import org.bukkit.World;

final class BukkitWorldSampler implements AStar.WorldSampler {

    private final World world;

    BukkitWorldSampler(World world) {
        this.world = world;
    }

    @Override
    public boolean solid(int x, int y, int z) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return false;
        }
        return world.getBlockAt(x, y, z).isSolid();
    }
}
