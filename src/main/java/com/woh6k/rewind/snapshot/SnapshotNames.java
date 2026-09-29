package com.woh6k.rewind.snapshot;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Naming rules shared by GUI-created and command-created snapshots. */
public final class SnapshotNames {
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm").withZone(ZoneId.systemDefault());

    private SnapshotNames() { }

    public static String slotName(SnapshotSlot slot) {
        return slot.type() == SnapshotType.AUTO ? "自动存档 " + slot.number() : "手动存档 " + slot.number();
    }

    public static String normalizeCustomName(String value) {
        if (value == null) return null;
        String result = value.trim().replaceAll("[\\p{Cntrl}]", "");
        if (result.isEmpty()) return null;
        if (result.length() > 64) throw new IllegalArgumentException("存档名称最长 64 个字符。");
        return result;
    }

    public static String displayName(SnapshotSlot slot, long createdAt, String customName) {
        return slotName(slot) + " " + (customName == null ? DISPLAY_TIME.format(Instant.ofEpochMilli(createdAt)) : customName);
    }

    public static String directoryName(SnapshotSlot slot, long createdAt, String customName) {
        // Disk names intentionally stay ASCII and independent of the user-facing remark.
        String prefix = slot.type() == SnapshotType.AUTO ? "auto_" : "manual_";
        return prefix + slot.number() + "-" + FILE_TIME.format(Instant.ofEpochMilli(createdAt));
    }
}
