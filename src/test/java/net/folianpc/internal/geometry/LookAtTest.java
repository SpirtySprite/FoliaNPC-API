package net.folianpc.internal.geometry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LookAtTest {

    private static final float EPS = 0.01f;

    @Test
    void facesSouthAtZeroYaw() {
        // +Z is south, yaw 0.
        LookAt.Rotation r = LookAt.face(0, 0, 0, 0, 0, 10);
        assertEquals(0f, r.yaw(), EPS);
        assertEquals(0f, r.pitch(), EPS);
    }

    @Test
    void facesWestAtNinetyYaw() {
        LookAt.Rotation r = LookAt.face(0, 0, 0, -10, 0, 0);
        assertEquals(90f, r.yaw(), EPS);
    }

    @Test
    void facesEastAtMinusNinety() {
        LookAt.Rotation r = LookAt.face(0, 0, 0, 10, 0, 0);
        assertEquals(-90f, r.yaw(), EPS);
    }

    @Test
    void facesNorthAtOneEighty() {
        LookAt.Rotation r = LookAt.face(0, 0, 0, 0, 0, -10);
        assertEquals(180f, Math.abs(r.yaw()), EPS);
    }

    @Test
    void pitchPositiveWhenTargetBelow() {
        LookAt.Rotation r = LookAt.face(0, 10, 0, 0, 0, 5);
        assertEquals(true, r.pitch() > 0, "looking down should be positive pitch");
    }

    @Test
    void pitchNegativeWhenTargetAbove() {
        LookAt.Rotation r = LookAt.face(0, 0, 0, 0, 10, 5);
        assertEquals(true, r.pitch() < 0, "looking up should be negative pitch");
    }

    @Test
    void pitchStraightDownIsNinety() {
        LookAt.Rotation r = LookAt.face(0, 10, 0, 0, 0, 0);
        assertEquals(90f, r.pitch(), EPS);
    }

    @Test
    void yawNormalizationWraps() {
        assertEquals(-90f, LookAt.normalizeYaw(270f), EPS);
        assertEquals(0f, LookAt.normalizeYaw(360f), EPS);
        assertEquals(170f, LookAt.normalizeYaw(-190f), EPS);
    }
}
