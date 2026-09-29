package com.woh6k.rewind.snapshot;

import java.nio.file.Path;

/** A validated snapshot directory paired with its metadata. */
public record SnapshotInfo(SnapshotSlot slot, SnapshotMetadata metadata, Path directory) {
}
