package net.folianpc.api;

public record NametagStyle(int background, int textOpacity, boolean shadow, boolean seeThrough) {

    public static final int VANILLA_BACKGROUND = 0x40000000;
    public static final int OPAQUE = 255;

    private static final NametagStyle DEFAULTS = new NametagStyle(VANILLA_BACKGROUND, OPAQUE, false, false);

    public NametagStyle {
        textOpacity = channel(textOpacity);
    }

    public static NametagStyle defaults() {
        return DEFAULTS;
    }

    public static NametagStyle transparent() {
        return DEFAULTS.withBackground(0);
    }

    public NametagStyle withBackground(int argb) {
        return new NametagStyle(argb, textOpacity, shadow, seeThrough);
    }

    public NametagStyle withBackground(int rgb, int alpha) {
        return withBackground(channel(alpha) << 24 | rgb & 0xFFFFFF);
    }

    public NametagStyle withTextOpacity(int opacity) {
        return new NametagStyle(background, opacity, shadow, seeThrough);
    }

    public NametagStyle withShadow(boolean value) {
        return new NametagStyle(background, textOpacity, value, seeThrough);
    }

    public NametagStyle withSeeThrough(boolean value) {
        return new NametagStyle(background, textOpacity, shadow, value);
    }

    public int backgroundAlpha() {
        return background >>> 24;
    }

    public int backgroundRgb() {
        return background & 0xFFFFFF;
    }

    private static int channel(int value) {
        return Math.max(0, Math.min(OPAQUE, value));
    }
}
