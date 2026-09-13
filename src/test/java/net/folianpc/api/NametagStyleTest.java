package net.folianpc.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NametagStyleTest {

    @Test
    void defaultsMatchVanillaTextDisplays() {
        NametagStyle style = NametagStyle.defaults();
        assertEquals(0x40000000, style.background());
        assertEquals(64, style.backgroundAlpha());
        assertEquals(0, style.backgroundRgb());
        assertEquals(255, style.textOpacity());
        assertFalse(style.shadow());
        assertFalse(style.seeThrough());
    }

    @Test
    void transparentOnlyClearsTheBackground() {
        NametagStyle style = NametagStyle.transparent();
        assertEquals(0, style.background());
        assertEquals(255, style.textOpacity());
    }

    @Test
    void backgroundPacksColourAndAlpha() {
        NametagStyle style = NametagStyle.defaults().withBackground(0x12AB34, 128);
        assertEquals(0x8012AB34, style.background());
        assertEquals(128, style.backgroundAlpha());
        assertEquals(0x12AB34, style.backgroundRgb());
        assertEquals(0xFF123456, NametagStyle.defaults().withBackground(0x00123456, 999).background());
        assertEquals(0x00FFFFFF, NametagStyle.defaults().withBackground(0xFFFFFF, -5).background());
    }

    @Test
    void textOpacityIsClampedToAByteChannel() {
        assertEquals(255, NametagStyle.defaults().withTextOpacity(1000).textOpacity());
        assertEquals(0, NametagStyle.defaults().withTextOpacity(-1).textOpacity());
        assertEquals(90, new NametagStyle(0, 90, true, true).textOpacity());
    }

    @Test
    void withersKeepEveryOtherField() {
        NametagStyle style = NametagStyle.transparent().withTextOpacity(100).withShadow(true).withSeeThrough(true);
        assertEquals(0, style.background());
        assertEquals(100, style.textOpacity());
        assertTrue(style.shadow());
        assertTrue(style.seeThrough());
        assertEquals(style, style.withShadow(true));
    }
}
