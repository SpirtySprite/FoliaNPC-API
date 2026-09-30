package net.folianpc.internal;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;

/**
 * Keeps {@link PlayerTracker} positions fresh for players who are riding something.
 *
 * <p>Movement events for a rider are reported as vehicle movement, so a listener that only
 * watches player movement would leave a riding player's tracked position stale and NPCs would
 * not appear or disappear for them as they travel.
 */
public final class PassengerTracking {

    private PassengerTracking() {
    }

    /** Refreshes every player riding {@code vehicle} when it has moved to a different block. */
    public static void onVehicleMoved(Vehicle vehicle, Location from, Location to, PlayerTracker tracker) {
        if (to == null || sameBlock(from, to)) {
            return;
        }
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof Player player) {
                tracker.refresh(player);
            }
        }
    }

    static boolean sameBlock(Location from, Location to) {
        return from != null
                && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ();
    }
}
