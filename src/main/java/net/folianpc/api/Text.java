package net.folianpc.api;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

// A string containing '<' is read as MiniMessage; anything else as legacy '&' codes, so older
// strings keep working.
public final class Text {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();

    private Text() {
    }

    public static Component parse(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        return text.indexOf('<') >= 0 ? MINI.deserialize(text) : LEGACY.deserialize(text);
    }

    public static Component mini(String miniMessage) {
        return MINI.deserialize(miniMessage);
    }

    public static Component legacy(String legacyText) {
        return LEGACY.deserialize(legacyText);
    }

    public static String toMini(Component component) {
        return MINI.serialize(component);
    }
}
