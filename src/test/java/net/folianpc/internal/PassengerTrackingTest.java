package net.folianpc.internal;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PassengerTrackingTest {

    private static Player rider(UUID id, Location at) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getLocation()).thenReturn(at);
        return player;
    }

    private static Location at(World world, double x, double y, double z) {
        return new Location(world, x, y, z);
    }

    @Test
    void aMovingVehicleRefreshesItsPlayerPassengers() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        UUID id = UUID.randomUUID();
        Player player = rider(id, at(world, 10.5, 64, 10.5));
        Vehicle horse = mock(Vehicle.class);
        when(horse.getPassengers()).thenReturn(List.of(player));
        PlayerTracker tracker = new PlayerTracker();

        PassengerTracking.onVehicleMoved(horse, at(world, 9.5, 64, 10.5), at(world, 10.5, 64, 10.5), tracker);

        PlayerTracker.Tracked tracked = tracker.get(id);
        assertNotNull(tracked, "a rider must be tracked even though no PlayerMoveEvent fires for them");
        assertEquals(10.5, tracked.x());
        assertEquals("world", tracked.world());
    }

    @Test
    void movingWithinTheSameBlockDoesNothing() {
        World world = mock(World.class);
        UUID id = UUID.randomUUID();
        Player player = rider(id, at(world, 10.9, 64, 10.1));
        Vehicle boat = mock(Vehicle.class);
        when(boat.getPassengers()).thenReturn(List.of(player));
        PlayerTracker tracker = new PlayerTracker();

        PassengerTracking.onVehicleMoved(boat, at(world, 10.1, 64, 10.1), at(world, 10.9, 64, 10.9), tracker);

        assertNull(tracker.get(id));
    }

    @Test
    void nonPlayerPassengersAreIgnored() {
        World world = mock(World.class);
        Entity pig = mock(Entity.class);
        Vehicle cart = mock(Vehicle.class);
        when(cart.getPassengers()).thenReturn(List.of(pig));
        PlayerTracker tracker = new PlayerTracker();

        PassengerTracking.onVehicleMoved(cart, at(world, 0, 64, 0), at(world, 5, 64, 0), tracker);

        assertEquals(0, tracker.size());
    }

    @Test
    void aMissingDestinationIsIgnored() {
        Vehicle cart = mock(Vehicle.class);
        PlayerTracker tracker = new PlayerTracker();

        PassengerTracking.onVehicleMoved(cart, null, null, tracker);

        assertEquals(0, tracker.size());
    }
}
