package net.folianpc.internal.pathfinding;

import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

public final class RoutePlanner {

    private static final double JUMP_ARC_HEIGHT = 0.35;

    private final int maxNodes;
    private final int maxRadius;

    public RoutePlanner() {
        this(4000, 128);
    }

    public RoutePlanner(int maxNodes, int maxRadius) {
        this.maxNodes = maxNodes;
        this.maxRadius = maxRadius;
    }

    public List<double[]> route(World world, double fromX, double fromY, double fromZ,
                                double toX, double toY, double toZ) {
        BukkitWorldSampler sampler = new BukkitWorldSampler(world);
        AStar.Node start = new AStar.Node(floor(fromX), floor(fromY), floor(fromZ));
        AStar.Node goal = new AStar.Node(floor(toX), floor(toY), floor(toZ));
        List<AStar.Node> path = AStar.find(sampler, start, goal, maxNodes, maxRadius);
        if (path.isEmpty()) {
            return List.of();
        }
        List<double[]> waypoints = new ArrayList<>(path.size());
        AStar.Node previous = null;
        for (AStar.Node node : path) {
            if (previous != null && node.y() > previous.y()) {
                waypoints.add(arcPeak(previous, node));
            }
            waypoints.add(new double[]{node.x() + 0.5, node.y(), node.z() + 0.5});
            previous = node;
        }
        waypoints.set(waypoints.size() - 1, new double[]{toX, toY, toZ});
        return waypoints;
    }

    private static double[] arcPeak(AStar.Node from, AStar.Node to) {
        double midX = (from.x() + to.x()) / 2.0 + 0.5;
        double midZ = (from.z() + to.z()) / 2.0 + 0.5;
        return new double[]{midX, to.y() + JUMP_ARC_HEIGHT, midZ};
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
