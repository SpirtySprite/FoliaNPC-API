package net.folianpc.internal.protocol.nms;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collection;

/** Hides an NPC's built-in name plate by putting its profile name on a team set to NEVER show tags. */
final class Teams {

    private final Class<?> visibilityClass;
    private final Class<?> collisionClass;
    private final Class<?> formattingClass;
    private final Constructor<?> scoreboardCtor;
    private final Constructor<?> teamCtor;
    private final Method setVisibility;
    private final Method setCollision;
    private final Method setColor;
    private final Method members;
    private final Method createPacket;
    private final Method removePacket;

    Teams() {
        Class<?> scoreboard = Reflect.nms("world.scores", "Scoreboard", "Scoreboard");
        Class<?> playerTeam = Reflect.nms("world.scores", "PlayerTeam", "ScoreboardTeam");
        Class<?> team = Reflect.nms("world.scores", "Team", "ScoreboardTeamBase");
        Class<?> packet = Reflect.nms("network.protocol.game",
                "ClientboundSetPlayerTeamPacket", "PacketPlayOutScoreboardTeam");

        this.visibilityClass = Nms.nested(team, "Visibility", "EnumNameTagVisibility");
        this.collisionClass = Nms.nested(team, "CollisionRule", "EnumTeamPush");
        this.formattingClass = Reflect.tryClass("net.minecraft.ChatFormatting");
        this.scoreboardCtor = Reflect.constructor(scoreboard);
        this.teamCtor = Reflect.constructor(playerTeam, scoreboard, String.class);
        this.setVisibility = Reflect.method(playerTeam, "setNameTagVisibility", visibilityClass);
        this.setCollision = Reflect.method(playerTeam, "setCollisionRule", collisionClass);
        this.setColor = Reflect.method(playerTeam, "setColor", formattingClass);
        this.members = Reflect.methodReturning(playerTeam, Collection.class);
        this.createPacket = Reflect.method(packet, "createAddOrModifyPacket", playerTeam, boolean.class);
        this.removePacket = Reflect.method(packet, "createRemovePacket", playerTeam);
    }

    /** Client-side teams are permanent until removed, so every created team needs this on despawn. */
    Object removePacket(String profileName) {
        return Reflect.invoke(removePacket, null, team(profileName));
    }

    private Object team(String profileName) {
        return Reflect.newInstance(teamCtor, Reflect.newInstance(scoreboardCtor), "fnpc_" + profileName);
    }

    @SuppressWarnings("unchecked")
    Object packet(String profileName, boolean nameVisible, String color, boolean collidable) {
        Object team = team(profileName);
        Reflect.invoke(setVisibility, team,
                Reflect.enumConstant(visibilityClass, nameVisible ? "ALWAYS" : "NEVER"));
        Reflect.invoke(setCollision, team,
                Reflect.enumConstant(collisionClass, collidable ? "ALWAYS" : "NEVER"));
        if (color != null) {
            Reflect.invoke(setColor, team, Reflect.enumConstant(formattingClass, color.toUpperCase()));
        }
        ((Collection<Object>) Reflect.invoke(members, team)).add(profileName);
        return Reflect.invoke(createPacket, null, team, true);
    }
}
