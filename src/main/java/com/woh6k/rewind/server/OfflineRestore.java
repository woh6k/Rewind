package com.woh6k.rewind.server;

import com.google.gson.Gson;
import com.woh6k.rewind.snapshot.*;
import net.minecraft.util.DirectoryLock;
import java.nio.file.*;
import java.io.IOException;
import java.util.UUID;

/** Durable restore intent. Applied only before loading a world, while holding its session lock. */
public final class OfflineRestore {
    private static final Gson JSON = new Gson();
    public record Plan(String world, String key, String slot, String snapshotId, String phase) {}
    public static void write(Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName()+".new");
        Files.writeString(temp, JSON.toJson(value));
        try (var channel = java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
        Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    public static Path journal(SnapshotStorage storage, String key) { return storage.rootDirectory().resolve("pending").resolve(key+".json"); }
    public static void applyAll() {
        SnapshotStorage storage = new SnapshotStorage();
        Path pending=storage.rootDirectory().resolve("pending");
        if (!Files.isDirectory(pending)) return;
        try (var files=Files.list(pending)) {
            for (Path file:files.filter(p->p.toString().endsWith(".json")).toList()) apply(storage,file);
        } catch (Exception e) { throw new IllegalStateException("Rewind pending restore failed; refusing to load worlds. Keep rewind/pending and recovery for repair.",e); }
    }

    /** Prevents a failed in-session restore from being bypassed by opening the world again. */
    public static void guardWorldOpening(Path candidate) {
        Path world = candidate.toAbsolutePath().normalize();
        Path pending = new SnapshotStorage().rootDirectory().resolve("pending");
        if (!Files.isDirectory(pending)) return;
        try (var files = Files.list(pending)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).toList()) {
                Plan plan = JSON.fromJson(Files.readString(file), Plan.class);
                if (plan != null && plan.world != null && Path.of(plan.world).toAbsolutePath().normalize().equals(world))
                    throw new IllegalStateException("Rewind restore for this world is unfinished. Restart the game/server so recovery can run; do not open the world manually.");
            }
        } catch (IOException error) { throw new IllegalStateException("Cannot check Rewind restore journal", error); }
    }
    public static void apply(SnapshotStorage storage, Path file) throws IOException {
        Plan plan=JSON.fromJson(Files.readString(file),Plan.class);
        if (plan == null || plan.world() == null || plan.key() == null || plan.snapshotId() == null
                || plan.phase() == null || !java.util.Set.of("QUEUED", "APPLYING", "COMMITTED").contains(plan.phase()))
            throw new IOException("Invalid restore journal");
        storage.recoveryDirectory(plan.key());
        if (!file.toAbsolutePath().normalize().equals(journal(storage, plan.key()))) throw new IOException("Journal path mismatch");
        Path world=Path.of(plan.world()).toAbsolutePath().normalize();
        SnapshotStorage.rejectLinks(world);
        if (world.getParent()==null || world.startsWith(storage.rootDirectory()) || storage.rootDirectory().startsWith(world)) throw new IOException("Unsafe world path");
        try (DirectoryLock lock=DirectoryLock.create(world)) {
            Path recovery=storage.recoveryDirectory(plan.key());
            Path owner = WorldIdentity.owner(storage, plan.key());
            if (owner != null && !owner.equals(world)) throw new IOException("Restore target does not own this identity");
            String currentIdentity = WorldIdentity.read(world);
            if (!ServerSnapshots.key(world).equals(plan.key())) {
                // During APPLYING, data/ may already have been displaced. A different UUID is
                // always refused; an absent UUID must be proven from the verified original backup.
                if (!plan.phase().equals("APPLYING") || currentIdentity != null)
                    throw new IOException("World identity mismatch");
                storage.verifySnapshot(recovery);
                if (!plan.key().equals(WorldIdentity.read(recovery))) throw new IOException("Recovery identity mismatch");
            }
            if (plan.phase().equals("COMMITTED")) {
                if (!Files.isRegularFile(world.resolve("level.dat"))) throw new IOException("Committed world missing level.dat");
                Files.delete(file);
                return;
            }
            SnapshotInfo source=storage.readSlot(plan.key(),ServerSnapshots.parseSlot(plan.slot()));
            if(source==null || !source.metadata().snapshotId.equals(plan.snapshotId())) throw new IOException("Snapshot changed after confirmation");
            storage.verifySnapshot(source.directory());
            if(plan.phase().equals("QUEUED")) {
                Path backup=storage.createTemporaryDirectory(recovery);
                SnapshotStorage.CopyStatistics recoveryStatistics = storage.copyWorldToTemporary(world,backup);
                SnapshotMetadata meta=new SnapshotMetadata(); meta.snapshotId=UUID.randomUUID().toString(); meta.type="RECOVERY"; meta.slot=1; meta.worldName=world.getFileName().toString(); meta.createdAtEpochMillis=System.currentTimeMillis();
                meta.fileCount=recoveryStatistics.fileCount(); meta.totalBytes=recoveryStatistics.totalBytes();
                storage.finalizeTemporarySnapshot(backup,meta); storage.promoteTemporary(backup,recovery);
            } else if (!plan.phase().equals("APPLYING") || !storage.isComplete(recovery)) throw new IOException("Missing recovery backup or invalid journal");
            if (!plan.phase().equals("QUEUED")) storage.verifySnapshot(recovery);
            Path staging=storage.createTemporaryDirectory(recovery.resolveSibling("restore-stage"));
            storage.copyWorldToTemporary(source.directory(),staging);
            write(file,new Plan(plan.world(),plan.key(),plan.slot(),plan.snapshotId(),"APPLYING"));
            // Preserve displaced files outside saves. Retrying APPLYING never replaces the recovery backup.
            Path displaced=Files.createDirectory(recovery.resolveSibling("displaced-"+UUID.randomUUID()));
            try(var entries=Files.list(world)) { for(Path entry:entries.toList()) if(!entry.getFileName().toString().equals("session.lock")) Files.move(entry,displaced.resolve(entry.getFileName())); }
            try(var entries=Files.list(staging)) { for(Path entry:entries.toList()) if(!entry.getFileName().toString().equals("snapshot.json")&&!entry.getFileName().toString().equals("COMPLETE")&&!entry.getFileName().toString().equals("checksums.sha256")) Files.move(entry,world.resolve(entry.getFileName())); }
            // Old snapshots may predate world IDs. Restore the current identity before committing.
            if (plan.key().startsWith("world-")) {
                Path id = world.resolve("data/rewind-world-id.txt");
                Files.createDirectories(id.getParent());
                Files.writeString(id, UUID.fromString(plan.key().substring(6)) + "\n");
            }
            write(file,new Plan(plan.world(),plan.key(),plan.slot(),plan.snapshotId(),"COMMITTED"));
            Files.delete(file);
            SnapshotStorage.deleteRecursively(staging);
        }
    }
}
