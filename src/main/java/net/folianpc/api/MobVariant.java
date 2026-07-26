package net.folianpc.api;

// Grouped the same way NpcAppearance groups cosmetic state, since this is otherwise five more loose
// fields on NpcData. variant/variantName are mutually exclusive depending on entity type - see Npc.
public record MobVariant(int variant, String variantName,
                         String villagerProfession, String villagerType, int villagerLevel) {

    public static MobVariant defaults() {
        return new MobVariant(0, null, null, null, 1);
    }
}
