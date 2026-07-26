package net.folianpc.internal.protocol.nms;

import net.folianpc.api.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Text-display entities, used for the floating nametag lines. */
final class Displays {

    private static final byte BILLBOARD_CENTER = 3;
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.builder()
            .character('§')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private final Metadata metadata;
    private final Method literal;
    private final Method paperToVanilla;
    private final Object componentSerializer;
    private final int textIndex;
    private final int billboardIndex;

    Displays(Metadata metadata) {
        this.metadata = metadata;
        Class<?> display = Reflect.nms("world.entity", "Display", "Display");
        Class<?> textDisplay = Nms.nested(display, "TextDisplay", "TextDisplay");
        Class<?> componentClass = Reflect.nms("network.chat", "Component", "IChatBaseComponent");
        Class<?> serializers = Reflect.nms("network.syncher", "EntityDataSerializers", "DataWatcherRegistry");

        this.componentSerializer = Reflect.staticField(serializers, "COMPONENT");
        this.literal = Reflect.method(componentClass, "literal", String.class);
        this.textIndex = Metadata.indexOf(Reflect.staticField(textDisplay, "DATA_TEXT_ID"));
        this.billboardIndex = billboardIndex(display);
        this.paperToVanilla = paperBridge();
    }

    Object textPacket(int entityId, String text) {
        List<Object> values = new ArrayList<>(2);
        values.add(metadata.value(textIndex, componentSerializer, toVanilla(Text.parse(text))));
        if (billboardIndex >= 0) {
            values.add(metadata.value(billboardIndex, metadata.byteSerializer, BILLBOARD_CENTER));
        }
        return metadata.packet(entityId, values);
    }

    boolean richText() {
        return paperToVanilla != null;
    }

    /** Paper converts Adventure directly, keeping gradients; otherwise fall back to section codes. */
    private Object toVanilla(Component component) {
        if (paperToVanilla != null) {
            Object converted = Reflect.invoke(paperToVanilla, null, component);
            if (converted != null) {
                return converted;
            }
        }
        return Reflect.invoke(literal, null, SECTION.serialize(component));
    }

    private static Method paperBridge() {
        Class<?> paper = Reflect.tryClass("io.papermc.paper.adventure.PaperAdventure");
        if (paper == null) {
            return null;
        }
        try {
            return Reflect.method(paper, "asVanilla", Component.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int billboardIndex(Class<?> display) {
        try {
            return Metadata.indexOf(Reflect.staticField(display, "DATA_BILLBOARD_RENDER_CONSTRAINTS_ID"));
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
