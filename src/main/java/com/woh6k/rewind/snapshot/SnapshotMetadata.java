package com.woh6k.rewind.snapshot;

/** Serialized beside a snapshot; never stored in the copied Minecraft world itself. */
public final class SnapshotMetadata {
    public String snapshotId;
    public String type;
    public int slot;
    public String worldName;
    /** Human-readable name shown in the manager and used as the snapshot directory base. */
    public String displayName;
    /** Optional name supplied by /rewind commit; null means the timestamp default. */
    public String customName;
    public long createdAtEpochMillis;
    public long gameTime;
    public String playerDimension;
    public double playerX;
    public double playerY;
    public double playerZ;
    public String modVersion;
    public String minecraftVersion;
    public String state;
    public long fileCount;
    public long totalBytes;
    public long creationDurationMillis;

    public SnapshotMetadata() {
        // Gson requires a no-argument constructor.
    }
}
