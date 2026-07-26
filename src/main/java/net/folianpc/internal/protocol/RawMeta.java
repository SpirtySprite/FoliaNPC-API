package net.folianpc.internal.protocol;

import net.folianpc.api.MetadataType;

// A developer-supplied entity-metadata entry, for mob fields the typed API does not cover.
public record RawMeta(MetadataType type, Object value) {
}
