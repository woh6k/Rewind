package com.woh6k.rewind.server;

import com.woh6k.rewind.snapshot.*;
import java.nio.file.*;
import java.util.UUID;

/** Release audit probes, restricted to newly created build fixtures. */
public final class ReleaseAuditVerification {
    private static int issues;
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "rewind-release-audit-");
        Path world = Files.createDirectories(root.resolve("world/data")).getParent();
        Files.writeString(world.resolve("level.dat"), "snapshot-world");
        Files.writeString(world.resolve("data/rewind-world-id.txt"), UUID.randomUUID() + "\n");
        SnapshotStorage storage = new SnapshotStorage(root.resolve("rewind"));
        String key = WorldIdentity.ensure(storage, world);
        SnapshotSlot slot = SnapshotSlot.manual(1);
        Path dest = storage.newSlotDirectory(key, slot, "manual_1-2026-09-29_00-00");
        Path temp = storage.createTemporaryDirectory(dest);
        var stats = storage.copyWorldToTemporary(world, temp);
        SnapshotMetadata meta = new SnapshotMetadata();
        meta.snapshotId = "audit-snapshot"; meta.type = "MANUAL"; meta.slot = 1;
        meta.fileCount = stats.fileCount(); meta.totalBytes = stats.totalBytes();
        storage.finalizeTemporarySnapshot(temp, meta);
        storage.promoteSlotTemporary(key, slot, temp, dest);
        storage.verifySnapshot(dest);

        Path clone = Files.createDirectories(root.resolve("cloned-world/data")).getParent();
        Files.copy(world.resolve("data/rewind-world-id.txt"), clone.resolve("data/rewind-world-id.txt"));
        String cloneKey = WorldIdentity.ensure(storage, clone);
        report(!cloneKey.equals(key) && ServerSnapshots.key(world).equals(key), "copied world has independent storage identity");
        report(storage.listSnapshots(cloneKey).isEmpty(), "copy cannot see original snapshots");
        Path movedClone = root.resolve("moved-clone");
        Files.move(clone, movedClone);
        report(WorldIdentity.ensure(storage, movedClone).equals(cloneKey), "moving a world preserves identity");
        report(WorldIdentity.owner(storage, cloneKey).equals(movedClone), "moving a world updates registered path");

        new SlotVersion(slot, meta.snapshotId).requireCurrent(storage, key);
        boolean staleRejected = false;
        try { new SlotVersion(slot, "another-admin-old-version").requireCurrent(storage, key); }
        catch (java.io.IOException expected) { staleRejected = true; }
        report(staleRejected, "stale multiplayer confirmation rejected");
        boolean occupiedRejected = false;
        try { new SlotVersion(slot, "-").requireCurrent(storage, key); }
        catch (java.io.IOException expected) { occupiedRejected = true; }
        report(occupiedRejected, "empty-slot save cannot overwrite another admin's new snapshot");

        Path extra = dest.resolve("unexpected-mod-data.dat");
        Files.writeString(extra, "not covered by the manifest");
        boolean rejected = false;
        try { storage.verifySnapshot(dest); } catch (java.io.IOException expected) { rejected = true; }
        report(rejected, "unlisted snapshot file rejected by integrity verification");
        Files.delete(extra);
        Path manifest = dest.resolve("checksums.sha256");
        String originalManifest = Files.readString(manifest);
        Files.writeString(manifest, originalManifest + originalManifest.lines().findFirst().orElseThrow() + "\n");
        boolean duplicateRejected = false;
        try { storage.verifySnapshot(dest); } catch (java.io.IOException expected) { duplicateRejected = true; }
        report(duplicateRejected, "duplicate manifest entry rejected");
        Files.writeString(manifest, originalManifest);

        Files.writeString(world.resolve("level.dat"), "pre-restore-world");
        Path journal = OfflineRestore.journal(storage, key);
        OfflineRestore.write(journal, new OfflineRestore.Plan(world.toString(), key, slot.id(), meta.snapshotId, "QUEUED"));
        OfflineRestore.apply(storage, journal);
        report(Files.readString(world.resolve("level.dat")).equals("snapshot-world"), "ordinary UUID-world restore");
        String foreignId = UUID.randomUUID().toString();
        Files.writeString(world.resolve("data/rewind-world-id.txt"), foreignId + "\n");
        OfflineRestore.write(journal, new OfflineRestore.Plan(world.toString(), key, slot.id(), meta.snapshotId, "APPLYING"));
        boolean foreignRejected = false;
        try { OfflineRestore.apply(storage, journal); } catch (java.io.IOException expected) { foreignRejected = true; }
        report(foreignRejected && Files.readString(world.resolve("data/rewind-world-id.txt")).trim().equals(foreignId), "APPLYING refuses a different world identity without modifying it");
        Files.writeString(world.resolve("data/rewind-world-id.txt"), key.substring(6) + "\n");

        // Simulate interruption after the data directory has been displaced, before staging is moved back.
        Path displacedData = root.resolve("interrupted-data");
        Files.move(world.resolve("data"), displacedData);
        OfflineRestore.write(journal, new OfflineRestore.Plan(world.toString(), key, slot.id(), meta.snapshotId, "APPLYING"));
        boolean recovered = true;
        try { OfflineRestore.apply(storage, journal); }
        catch (java.io.IOException expected) { recovered = false; System.out.println("DETAIL: " + expected.getMessage()); }
        report(recovered, "interrupted restore retries after world identity file was displaced");
        if (!Files.exists(world.resolve("data"))) Files.move(displacedData, world.resolve("data"));

        OfflineRestore.write(journal, new OfflineRestore.Plan(world.toString(), key, slot.id(), meta.snapshotId, "COMMITTED"));
        storage.deleteSlot(key, slot);
        boolean committedCleared = true;
        try { OfflineRestore.apply(storage, journal); }
        catch (java.io.IOException expected) { committedCleared = false; System.out.println("DETAIL: " + expected.getMessage()); }
        report(committedCleared && !Files.exists(journal), "committed journal finalizes without original snapshot");
        Path recoveryFolder = storage.recoveryDirectory(key).getParent();
        Path displaced = Files.createDirectories(recoveryFolder.resolve("displaced-" + UUID.randomUUID()));
        Files.writeString(displaced.resolve("old-level.dat"), "old world copy");
        Path unrelated = Files.createDirectories(recoveryFolder.resolve("notes-not-managed-by-rewind"));
        Files.writeString(unrelated.resolve("keep.txt"), "keep");
        Path otherWorld = Files.createDirectories(storage.recoveryDirectory(cloneKey).getParent().resolve("displaced-" + UUID.randomUUID()));
        Files.writeString(otherWorld.resolve("keep.txt"), "keep");
        OfflineRestore.write(journal, new OfflineRestore.Plan(world.toString(), key, slot.id(), meta.snapshotId, "QUEUED"));
        boolean pendingBlocked = false;
        try { storage.cleanupDisplaced(key); } catch (java.io.IOException expected) { pendingBlocked = true; }
        report(pendingBlocked && Files.exists(displaced), "manual cleanup blocked while restore journal exists");
        Files.delete(journal);
        int removed = storage.cleanupDisplaced(key);
        report(removed >= 1 && !Files.exists(displaced), "manual cleanup deletes completed displaced copies");
        report(Files.isRegularFile(storage.recoveryDirectory(key).resolve("level.dat")), "manual cleanup retains latest recovery backup");
        report(Files.exists(unrelated.resolve("keep.txt")) && Files.exists(otherWorld.resolve("keep.txt")), "manual cleanup leaves unrelated and other-world files");
        report(storage.cleanupDisplaced(key) == 0, "repeating cleanup is safe");
        report(new ServerSettings().intervalMinutes == 10, "default interval is ten minutes");
        System.out.println("AUDIT: " + issues + " issues reproduced. Fixture: " + root);
        if (issues != 0) throw new AssertionError("Release audit reproduced " + issues + " issues");
    }
    private static void report(boolean passed, String description) {
        System.out.println((passed ? "PASS: " : "ISSUE: ") + description);
        if (!passed) issues++;
    }
}
