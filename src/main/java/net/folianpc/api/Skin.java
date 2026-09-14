package net.folianpc.api;

public record Skin(String value, String signature) {

    public static Skin of(String value, String signature) {
        return new Skin(value, signature);
    }
}
