package com.woh6k.rewind.server;

import com.woh6k.rewind.snapshot.SnapshotMetadata;
import java.util.List;

/** Network projection: contains no local paths or world files. */
public record ServerView(boolean administrator, String world, String state, ServerSettings settings,
                         List<SnapshotMetadata> snapshots) {}
