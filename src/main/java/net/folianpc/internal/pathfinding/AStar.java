package net.folianpc.internal.pathfinding;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

// Pure logic - takes a WorldSampler so it unit-tests against a fake grid instead of a live world.
public final class AStar {

    public interface WorldSampler {
        boolean solid(int x, int y, int z);
    }

    public record Node(int x, int y, int z) {
    }

    private static final int[][] DIRECTIONS = {
            {0, 1}, {0, -1}, {1, 0}, {-1, 0},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private static final int MAX_STEP_UP = 1;
    private static final int MAX_STEP_DOWN = 3;

    private AStar() {
    }

    public static List<Node> find(WorldSampler world, Node start, Node goal, int maxNodes, int maxRadius) {
        Map<Node, Node> cameFrom = new HashMap<>();
        Map<Node, Double> gScore = new HashMap<>();
        Set<Node> closed = new HashSet<>();
        PriorityQueue<Node> open = new PriorityQueue<>(
                Comparator.comparingDouble(n -> score(gScore, n) + heuristic(n, goal)));

        gScore.put(start, 0.0);
        open.add(start);
        int explored = 0;

        while (!open.isEmpty() && explored < maxNodes) {
            Node current = open.poll();
            if (!closed.add(current)) {
                continue;
            }
            if (current.equals(goal)) {
                return reconstruct(cameFrom, current);
            }
            explored++;

            for (Node neighbor : neighbors(world, current)) {
                if (closed.contains(neighbor)
                        || Math.abs(neighbor.x() - start.x()) > maxRadius
                        || Math.abs(neighbor.z() - start.z()) > maxRadius) {
                    continue;
                }
                double tentative = score(gScore, current) + stepCost(current, neighbor);
                if (tentative < score(gScore, neighbor)) {
                    cameFrom.put(neighbor, current);
                    gScore.put(neighbor, tentative);
                    open.add(neighbor);
                }
            }
        }
        return List.of();
    }

    private static double score(Map<Node, Double> gScore, Node n) {
        return gScore.getOrDefault(n, Double.MAX_VALUE);
    }

    private static List<Node> reconstruct(Map<Node, Node> cameFrom, Node current) {
        Deque<Node> path = new ArrayDeque<>();
        path.addFirst(current);
        Node at = current;
        while (cameFrom.containsKey(at)) {
            at = cameFrom.get(at);
            path.addFirst(at);
        }
        return new ArrayList<>(path);
    }

    private static double heuristic(Node a, Node b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double stepCost(Node from, Node to) {
        double horizontal = (from.x() != to.x() && from.z() != to.z()) ? 1.4142 : 1.0;
        return horizontal + Math.abs(to.y() - from.y()) * 0.5;
    }

    private static List<Node> neighbors(WorldSampler world, Node from) {
        List<Node> result = new ArrayList<>(8);
        for (int[] dir : DIRECTIONS) {
            Node landing = landingSpot(world, from, dir[0], dir[1]);
            if (landing != null) {
                result.add(landing);
            }
        }
        return result;
    }

    private static Node landingSpot(WorldSampler world, Node from, int dx, int dz) {
        boolean diagonal = dx != 0 && dz != 0;
        if (diagonal && !openCorner(world, from, dx, dz)) {
            return null;
        }
        for (int dy = MAX_STEP_UP; dy >= -MAX_STEP_DOWN; dy--) {
            Node candidate = new Node(from.x() + dx, from.y() + dy, from.z() + dz);
            if (standable(world, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean openCorner(WorldSampler world, Node from, int dx, int dz) {
        return !world.solid(from.x() + dx, from.y(), from.z()) && !world.solid(from.x() + dx, from.y() + 1, from.z())
                && !world.solid(from.x(), from.y(), from.z() + dz) && !world.solid(from.x(), from.y() + 1, from.z() + dz);
    }

    private static boolean standable(WorldSampler world, Node n) {
        return world.solid(n.x(), n.y() - 1, n.z())
                && !world.solid(n.x(), n.y(), n.z())
                && !world.solid(n.x(), n.y() + 1, n.z());
    }
}
