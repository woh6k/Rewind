package com.woh6k.rewind.server;

import com.woh6k.rewind.snapshot.*;
import java.io.IOException;

/** Optimistic concurrency check for destructive slot operations; '-' denotes an empty slot. */
public record SlotVersion(SnapshotSlot slot, String snapshotId) {
    public static SlotVersion parse(String value) {
        String[] parts = value.split(":", 2);
        if (parts.length != 2 || parts[1].isBlank()) throw new IllegalArgumentException("Missing snapshot version");
        return new SlotVersion(ServerSnapshots.parseSlot(parts[0]), parts[1]);
    }
    public static String id(SnapshotInfo info) { return info == null ? "-" : info.metadata().snapshotId; }
    public void requireCurrent(SnapshotStorage storage, String worldKey) throws IOException {
        if (!snapshotId.equals(id(storage.readSlot(worldKey, slot))))
            throw new IOException("存档已被其他操作修改，请刷新后重新确认。");
    }
}
