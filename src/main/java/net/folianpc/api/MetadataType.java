package net.folianpc.api;

// Covers flags and simple integer variants; composite fields (e.g. villager data) aren't reachable this way.
public enum MetadataType {
    BYTE,
    INT,
    BOOLEAN,
    FLOAT
}
