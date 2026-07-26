package net.folianpc.internal.pathfinding;

import org.bukkit.World;

final class BukkitWorldSampler implements AStar.WorldSampler {

    private final World world;

    BukkitWorldSampler(World world) {
        this.world = world;
    }

    @Override
    public boolean solid(int x, int y, int z) {
        // Above/below the world is open space (void or sky), not an obstruction - but also never a
        // floor, so standable() still correctly refuses to plant a landing spot out there.
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return false;
        }
        return world.getBlockAt(x, y, z).isSolid();
    }
}
