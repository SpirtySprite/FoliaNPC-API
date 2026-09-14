package net.folianpc.internal.protocol;

import net.folianpc.api.MetadataType;

public record RawMeta(MetadataType type, Object value) {
}
