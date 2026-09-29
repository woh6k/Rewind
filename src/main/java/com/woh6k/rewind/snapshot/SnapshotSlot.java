package com.woh6k.rewind.snapshot;

import java.util.Locale;

/** A fixed user-visible slot. There are three automatic and five manual slots. */
public record SnapshotSlot(SnapshotType type, int number) {
    public SnapshotSlot {
        int maximum = type == SnapshotType.AUTO ? 3 : type == SnapshotType.MANUAL ? 5 : 1;
        if (number < 1 || number > maximum) {
            throw new IllegalArgumentException("Invalid " + type + " snapshot slot: " + number);
        }
    }

    public String id() {
        return type.name().toLowerCase(Locale.ROOT) + "_" + number;
    }

    public static SnapshotSlot manual(int number) {
        return new SnapshotSlot(SnapshotType.MANUAL, number);
    }

    public static SnapshotSlot automatic(int number) {
        return new SnapshotSlot(SnapshotType.AUTO, number);
    }
}
