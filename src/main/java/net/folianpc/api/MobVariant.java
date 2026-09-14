package net.folianpc.api;

public record MobVariant(int variant, String variantName,
                         String villagerProfession, String villagerType, int villagerLevel) {

    public static MobVariant defaults() {
        return new MobVariant(0, null, null, null, 1);
    }
}
