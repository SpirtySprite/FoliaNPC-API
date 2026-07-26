package net.folianpc.api;

import java.util.ArrayList;
import java.util.List;

// Which optional subsystems bound successfully on this server. Each degrades on its own, so an NPC
// still works when one is missing - check this if you want to warn users or fall back instead.
public record Capabilities(boolean skins, boolean nametags, boolean namePlateHiding,
                           boolean equipment, boolean scale, boolean richText, boolean baby,
                           boolean mobVariants, boolean villagerData) {

    public boolean complete() {
        return missing().isEmpty();
    }

    public List<String> missing() {
        List<String> missing = new ArrayList<>();
        if (!skins) {
            missing.add("skins");
        }
        if (!nametags) {
            missing.add("nametags");
        }
        if (!namePlateHiding) {
            missing.add("name plate hiding");
        }
        if (!equipment) {
            missing.add("equipment");
        }
        if (!scale) {
            missing.add("scale");
        }
        if (!richText) {
            missing.add("rich text");
        }
        if (!baby) {
            missing.add("baby state");
        }
        if (!mobVariants) {
            missing.add("mob variants");
        }
        if (!villagerData) {
            missing.add("villager data");
        }
        return missing;
    }
}
