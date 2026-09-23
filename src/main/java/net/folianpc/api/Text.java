package net.folianpc.api;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

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
        return MINI.deserialize(Legacy.toMini(text));
    }

    public static String escape(String value) {
        return value == null ? "" : MINI.escapeTags(value);
    }

    public static String plain(Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component);
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
