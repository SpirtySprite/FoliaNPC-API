package net.folianpc.internal.protocol.nms;

import net.folianpc.api.NametagStyle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DisplaysStyleTest {

    @Test
    void fullyTransparentTextStaysBelowTheClientDiscardThreshold() {
        assertEquals(4, Displays.opacityByte(0));
        assertEquals(4, Displays.opacityByte(3));
        assertEquals(26, Displays.opacityByte(26));
        assertEquals((byte) 128, Displays.opacityByte(128));
        assertEquals((byte) 255, Displays.opacityByte(255));
    }

    @Test
    void styleFlagsFollowTheVanillaBitLayout() {
        assertEquals(0, Displays.styleFlags(NametagStyle.defaults()));
        assertEquals(1, Displays.styleFlags(NametagStyle.defaults().withShadow(true)));
        assertEquals(2, Displays.styleFlags(NametagStyle.defaults().withSeeThrough(true)));
        assertEquals(3, Displays.styleFlags(NametagStyle.defaults().withShadow(true).withSeeThrough(true)));
    }
}
