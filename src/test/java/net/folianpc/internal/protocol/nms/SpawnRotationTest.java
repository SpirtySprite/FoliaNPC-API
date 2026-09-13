package net.folianpc.internal.protocol.nms;

import net.folianpc.internal.geometry.LookAt;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpawnRotationTest {

    private static final UUID ID = UUID.randomUUID();

    @Test
    void floatPacketsCarryRealDegreesForBodyAndHead() {
        Object[] args = Nms.addEntityArguments(false, 7, ID, 1.0, 64.0, -2.0, 5.1f, 180.0f, "type", "zero");

        assertEquals(5.1f, args[5]);
        assertEquals(180.0f, args[6]);
        assertEquals(180.0, args[10]);
        assertEquals("type", args[7]);
        assertEquals(0, args[8]);
        assertEquals("zero", args[9]);
    }

    @Test
    void floatPacketsKeepNegativeAndFractionalYaw() {
        Object[] args = Nms.addEntityArguments(false, 7, ID, 0, 0, 0, 0.0f, -94.95f, "type", "zero");

        assertEquals(-94.95f, args[6]);
        assertEquals((double) -94.95f, args[10]);
    }

    @Test
    void bytePacketsEncodeAnglesForBodyAndHead() {
        Object[] args = Nms.addEntityArguments(true, 7, ID, 0, 0, 0, 45.0f, 90.0f, "type", "zero");

        assertEquals((byte) 32, args[5]);
        assertEquals((byte) 64, args[6]);
        assertEquals((byte) 64, args[10]);
    }

    @Test
    void angleBytesFloorLikeVanilla() {
        assertEquals((byte) -128, LookAt.angleByte(180.0f));
        assertEquals((byte) -128, LookAt.angleByte(-180.0f));
        assertEquals((byte) 64, LookAt.angleByte(90.0f));
        assertEquals((byte) -68, LookAt.angleByte(-94.95f));
        assertEquals((byte) 0, LookAt.angleByte(0.0f));
    }
}
