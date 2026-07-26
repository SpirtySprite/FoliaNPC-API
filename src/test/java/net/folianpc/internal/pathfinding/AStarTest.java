package net.folianpc.internal.pathfinding;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AStarTest {

    // A fake grid: an open floor at y=0 (so y=1 is standable everywhere) plus explicit solid blocks
    // added to build walls and ledges. No Bukkit involved.
    private static final class Grid implements AStar.WorldSampler {
        private final Set<Long> solid = new HashSet<>();

        void wall(int x1, int z1, int x2, int z2, int y) {
            for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    set(x, y, z);
                }
            }
        }

        void set(int x, int y, int z) {
            solid.add(key(x, y, z));
        }

        @Override
        public boolean solid(int x, int y, int z) {
            if (y == 0) {
                return true; // floor everywhere by default
            }
            return solid.contains(key(x, y, z));
        }

        private static long key(int x, int y, int z) {
            return ((long) (x + 30_000) << 40) | ((long) (y + 2_000) << 20) | (z + 30_000);
        }
    }

    private static AStar.Node at(int x, int y, int z) {
        return new AStar.Node(x, y, z);
    }

    @Test
    void findsADirectRouteOnOpenFlatGround() {
        Grid grid = new Grid();

        List<AStar.Node> route = AStar.find(grid, at(0, 1, 0), at(5, 1, 0), 4000, 128);

        assertTrue(route.size() >= 2);
        assertEquals(at(0, 1, 0), route.get(0));
        assertEquals(at(5, 1, 0), route.get(route.size() - 1));
    }

    @Test
    void routesAroundAWallInTheWay() {
        Grid grid = new Grid();
        grid.wall(3, -5, 3, 5, 1); // a wall at x=3 spanning z -5..5, blocking the straight line
        grid.wall(3, -5, 3, 5, 2);

        List<AStar.Node> route = AStar.find(grid, at(0, 1, 0), at(6, 1, 0), 4000, 128);

        assertTrue(route.size() > 2, "must detour, not walk straight through the wall");
        for (AStar.Node n : route) {
            boolean insideWallFootprint = n.x() == 3 && n.z() >= -5 && n.z() <= 5;
            assertTrue(!insideWallFootprint || n.y() > 2, "route must never pass through the wall itself");
        }
        assertEquals(at(6, 1, 0), route.get(route.size() - 1));
    }

    @Test
    void unreachableGoalReturnsNoRoute() {
        Grid grid = new Grid();
        grid.wall(-10, 3, 10, 3, 1); // seals the goal off in every direction
        grid.wall(-10, 3, 10, 3, 2);
        grid.wall(-10, -3, 10, -3, 1);
        grid.wall(-10, -3, 10, -3, 2);
        grid.wall(-10, -3, -10, 3, 1);
        grid.wall(-10, -3, -10, 3, 2);
        grid.wall(10, -3, 10, 3, 1);
        grid.wall(10, -3, 10, 3, 2);

        List<AStar.Node> route = AStar.find(grid, at(0, 1, 0), at(20, 1, 20), 4000, 128);

        assertTrue(route.isEmpty());
    }

    @Test
    void stepsUpASingleBlockLedge() {
        Grid grid = new Grid();
        // A single-layer ridge wide enough that detouring around either end costs far more than
        // stepping over it, so the cheaper route is up and over.
        grid.wall(3, -10, 3, 10, 1);

        List<AStar.Node> route = AStar.find(grid, at(0, 1, 0), at(5, 1, 0), 4000, 128);

        assertTrue(route.contains(at(3, 2, 0)), "must step up onto the ridge rather than detour around it");
        assertEquals(at(5, 1, 0), route.get(route.size() - 1));
    }

    @Test
    void stepsDownOffALedgeWithinTheFallLimit() {
        Grid grid = new Grid();
        // Raise the floor for x<3 by two blocks, so crossing x=3 is a two-block drop (within MAX_STEP_DOWN).
        grid.wall(-5, -5, 2, 5, 1);
        grid.wall(-5, -5, 2, 5, 2);

        List<AStar.Node> route = AStar.find(grid, at(0, 3, 0), at(5, 1, 0), 4000, 128);

        assertEquals(at(0, 3, 0), route.get(0));
        assertEquals(at(5, 1, 0), route.get(route.size() - 1));
    }

    @Test
    void refusesADropBiggerThanTheFallLimit() {
        Grid grid = new Grid();
        // A four-block-high wall the NPC would have to drop off - deeper than MAX_STEP_DOWN (3) - and
        // no other way around within the search radius.
        grid.wall(-20, -1, 2, 1, 1);
        grid.wall(-20, -1, 2, 1, 2);
        grid.wall(-20, -1, 2, 1, 3);
        grid.wall(-20, -1, 2, 1, 4);

        List<AStar.Node> route = AStar.find(grid, at(0, 5, 0), at(5, 1, 0), 4000, 30);

        assertTrue(route.isEmpty(), "a drop this deep must not be taken");
    }

    @Test
    void refusesToCutThroughADiagonalWallCorner() {
        Grid grid = new Grid();
        // Two solid columns forming an L; only a diagonal step could cut the corner between them.
        grid.set(1, 1, 0);
        grid.set(1, 2, 0);
        grid.set(0, 1, 1);
        grid.set(0, 2, 1);

        List<AStar.Node> route = AStar.find(grid, at(0, 1, 0), at(1, 1, 1), 4000, 128);

        assertTrue(route.isEmpty() || route.size() > 2, "must not cut the corner in a single diagonal step");
    }

    @Test
    void respectsTheSearchRadiusBound() {
        Grid grid = new Grid();

        List<AStar.Node> route = AStar.find(grid, at(0, 1, 0), at(500, 1, 0), 20000, 10);

        assertTrue(route.isEmpty(), "a reachable goal outside the radius must still be refused");
    }

    @Test
    void respectsTheNodeBudget() {
        Grid grid = new Grid();

        List<AStar.Node> route = AStar.find(grid, at(0, 1, 0), at(100, 1, 100), 5, 500);

        assertTrue(route.isEmpty(), "an exhausted node budget must give up rather than search forever");
    }

    @Test
    void trivialRouteWhenAlreadyAtTheGoal() {
        Grid grid = new Grid();

        List<AStar.Node> route = AStar.find(grid, at(2, 1, 2), at(2, 1, 2), 4000, 128);

        assertEquals(List.of(at(2, 1, 2)), route);
    }
}
