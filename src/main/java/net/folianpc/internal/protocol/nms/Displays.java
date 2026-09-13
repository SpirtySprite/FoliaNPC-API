package net.folianpc.internal.protocol.nms;

import net.folianpc.api.NametagStyle;
import net.folianpc.api.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Text-display entities, used for the floating nametag lines. */
final class Displays {

    private static final byte BILLBOARD_CENTER = 3;
    private static final byte FLAG_SHADOW = 0x01;
    private static final byte FLAG_SEE_THROUGH = 0x02;
    private static final int FIRST_RENDERED_OPACITY = 4;
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
    private final int backgroundIndex;
    private final int opacityIndex;
    private final int styleFlagsIndex;

    Displays(Metadata metadata) {
        this.metadata = metadata;
        Class<?> display = Reflect.nms("world.entity", "Display", "Display");
        Class<?> textDisplay = Nms.nested(display, "TextDisplay", "TextDisplay");
        Class<?> componentClass = Reflect.nms("network.chat", "Component", "IChatBaseComponent");
        Class<?> serializers = Reflect.nms("network.syncher", "EntityDataSerializers", "DataWatcherRegistry");

        this.componentSerializer = Reflect.staticField(serializers, "COMPONENT");
        this.literal = Reflect.method(componentClass, "literal", String.class);
        this.textIndex = Metadata.indexOf(Reflect.staticField(textDisplay, "DATA_TEXT_ID"));
        this.billboardIndex = optionalIndex(display, "DATA_BILLBOARD_RENDER_CONSTRAINTS_ID");
        this.backgroundIndex = metadata.intSerializer == null ? -1
                : optionalIndex(textDisplay, "DATA_BACKGROUND_COLOR_ID");
        this.opacityIndex = optionalIndex(textDisplay, "DATA_TEXT_OPACITY_ID");
        this.styleFlagsIndex = optionalIndex(textDisplay, "DATA_STYLE_FLAGS_ID");
        this.paperToVanilla = paperBridge();
    }

    Object textPacket(int entityId, String text, NametagStyle style) {
        NametagStyle applied = style == null ? NametagStyle.defaults() : style;
        List<Object> values = new ArrayList<>(5);
        values.add(metadata.value(textIndex, componentSerializer, toVanilla(Text.parse(text))));
        if (billboardIndex >= 0) {
            values.add(metadata.value(billboardIndex, metadata.byteSerializer, BILLBOARD_CENTER));
        }
        if (backgroundIndex >= 0) {
            values.add(metadata.value(backgroundIndex, metadata.intSerializer, applied.background()));
        }
        if (opacityIndex >= 0) {
            values.add(metadata.value(opacityIndex, metadata.byteSerializer, opacityByte(applied.textOpacity())));
        }
        if (styleFlagsIndex >= 0) {
            values.add(metadata.value(styleFlagsIndex, metadata.byteSerializer, styleFlags(applied)));
        }
        return metadata.packet(entityId, values);
    }

    static byte opacityByte(int opacity) {
        return (byte) Math.max(FIRST_RENDERED_OPACITY, Math.min(NametagStyle.OPAQUE, opacity));
    }

    static byte styleFlags(NametagStyle style) {
        byte flags = 0;
        if (style.shadow()) {
            flags |= FLAG_SHADOW;
        }
        if (style.seeThrough()) {
            flags |= FLAG_SEE_THROUGH;
        }
        return flags;
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

    private static int optionalIndex(Class<?> owner, String field) {
        try {
            return Metadata.indexOf(Reflect.staticField(owner, field));
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
