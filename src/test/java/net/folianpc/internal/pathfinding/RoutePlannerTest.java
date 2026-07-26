package net.folianpc.internal.pathfinding;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoutePlannerTest {

    // Floor at y<=0, open above, plus a single-block ridge at x=1 forcing a step-up from (0,1,0) to (3,1,0).
    private World worldWithARidgeAt(int ridgeX) {
        World world = mock(World.class);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        Block solid = mock(Block.class);
        when(solid.isSolid()).thenReturn(true);
        Block air = mock(Block.class);
        when(air.isSolid()).thenReturn(false);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(inv -> {
            int x = inv.getArgument(0);
            int y = inv.getArgument(1);
            if (y <= 0) {
                return solid;
            }
            return (x == ridgeX && y == 1) ? solid : air;
        });
        return world;
    }

    @Test
    void stepUpArcsThroughAPeakInsteadOfGlidingDiagonally() {
        RoutePlanner planner = new RoutePlanner();
        World world = worldWithARidgeAt(1);

        List<double[]> route = planner.route(world, 0, 1, 0, 3, 1, 0);

        assertTrue(route.size() >= 3, "expected at least a pre-step, an arc peak, and a landing waypoint");
        boolean hasArcPeak = route.stream().anyMatch(p -> p[1] > 2.0 && p[1] < 2.5);
        assertTrue(hasArcPeak, "a step-up leg must include a waypoint above the landing height");
    }

    @Test
    void flatGroundHasNoArcWaypoints() {
        RoutePlanner planner = new RoutePlanner();
        World world = worldWithARidgeAt(-1000); // no ridge actually reachable

        List<double[]> route = planner.route(world, 0, 1, 0, 3, 1, 0);

        for (double[] point : route) {
            assertEquals(1.0, point[1], 1e-6, "flat ground must never rise above walking height");
        }
    }
}
