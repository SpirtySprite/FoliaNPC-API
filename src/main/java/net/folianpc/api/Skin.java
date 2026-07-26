package net.folianpc.api;

// The signed texture pair Mojang hands out. Fetch one with FoliaNpc.fetchSkin(name), or build it
// directly if you already have the value/signature.
public record Skin(String value, String signature) {

    public static Skin of(String value, String signature) {
        return new Skin(value, signature);
    }
}
