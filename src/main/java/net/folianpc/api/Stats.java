package net.folianpc.api;

// viewerShows counts NPC-to-player pairings, so 10 NPCs each seen by 20 players reads 200.
public record Stats(int npcs, int viewerShows, long packetsSent, double lastTickMillis) {
}
