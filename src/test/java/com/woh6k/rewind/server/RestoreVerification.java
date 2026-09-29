package com.woh6k.rewind.server;
import com.woh6k.rewind.snapshot.*;
import java.nio.file.*;
import net.minecraft.util.DirectoryLock;

/** Standalone filesystem verification; never touches run/saves or real snapshots. */
public final class RestoreVerification {
    public static void main(String[] args) throws Exception {
        Path fixture=Files.createTempDirectory(Path.of("build").toAbsolutePath(),"rewind-verification-");
        Path world=Files.createDirectory(fixture.resolve("world"));
        Files.writeString(world.resolve("level.dat"),"before");
        SnapshotStorage storage=new SnapshotStorage(fixture.resolve("rewind"));
        String key=ServerSnapshots.key(world);
        SnapshotSlot slot=SnapshotSlot.manual(1);
        long createdAt = System.currentTimeMillis();
        Path dest=storage.newSlotDirectory(key,slot,SnapshotNames.directoryName(slot, createdAt, "主基地完工")), temp=storage.createTemporaryDirectory(dest);
        SnapshotStorage.CopyStatistics initialStatistics = storage.copyWorldToTemporary(world,temp);
        SnapshotMetadata meta=new SnapshotMetadata();meta.snapshotId="test-id";meta.type="MANUAL";meta.slot=1;meta.createdAtEpochMillis=createdAt;meta.customName="主基地完工";meta.displayName=SnapshotNames.displayName(slot, createdAt, meta.customName);
        meta.fileCount=initialStatistics.fileCount(); meta.totalBytes=initialStatistics.totalBytes();
        storage.finalizeTemporarySnapshot(temp,meta); storage.promoteSlotTemporary(key,slot,temp,dest);
        check(storage.slotDirectory(key,slot).equals(dest) && dest.getFileName().toString().matches("manual_1-\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}"), "ASCII timestamp snapshot directory name");
        check(meta.displayName.contains("主基地完工") && !dest.getFileName().toString().contains("主基地完工"), "custom name is metadata only");
        Files.writeString(world.resolve("level.dat"),"after"); Files.writeString(world.resolve("new-file"),"remove on restore");
        Path journal=OfflineRestore.journal(storage,key);
        OfflineRestore.write(journal,new OfflineRestore.Plan(world.toString(),key,slot.id(),meta.snapshotId,"QUEUED"));
        try(DirectoryLock lock=DirectoryLock.create(world)) {
            boolean refused=false;try { OfflineRestore.apply(storage,journal); } catch(java.io.IOException expected) { refused=true; }
            check(refused,"active-world lock must prevent restore");
            check(Files.readString(world.resolve("level.dat")).equals("after"),"locked world unchanged");
        }
        OfflineRestore.apply(storage,journal);
        check(Files.readString(world.resolve("level.dat")).equals("before"),"restore original directory");
        check(!Files.exists(world.resolve("new-file")),"remove newer files");
        check(Files.readString(storage.recoveryDirectory(key).resolve("level.dat")).equals("after"),"preserve pre-restore recovery");
        check(!Files.exists(journal),"commit journal cleared");
        check(storage.listSnapshots("another-world").isEmpty(),"world isolation");
        boolean invalid=false;try { ServerSnapshots.parseSlot("../../world"); } catch(IllegalArgumentException expected) { invalid=true; }
        check(invalid,"reject path as slot");
        Path retry=OfflineRestore.journal(storage,key);
        Files.writeString(world.resolve("level.dat"),"partial");
        OfflineRestore.write(retry,new OfflineRestore.Plan(world.toString(),key,slot.id(),meta.snapshotId,"APPLYING"));
        OfflineRestore.apply(storage,retry);
        check(Files.readString(world.resolve("level.dat")).equals("before"),"retry interrupted restore");
        check(Files.readString(storage.recoveryDirectory(key).resolve("level.dat")).equals("after"),"retry retains original backup");
        Files.writeString(dest.resolve("level.dat"), "tampered");
        boolean tamperDetected = false; try { storage.verifySnapshot(dest); } catch (java.io.IOException expected) { tamperDetected = true; }
        check(tamperDetected, "checksum detects changed snapshot data");
        storage.deleteSlot(key, slot);
        check(!Files.exists(dest), "GUI delete hard-deletes the active snapshot directory");
        String legacyKey = "legacy-world";
        SnapshotSlot legacySlot = SnapshotSlot.manual(2);
        long legacyCreatedAt = System.currentTimeMillis();
        Path legacyDestination = storage.newSlotDirectory(legacyKey, legacySlot, "手动存档 2 旧名称");
        Path legacyTemporary = storage.createTemporaryDirectory(legacyDestination);
        SnapshotStorage.CopyStatistics legacyStatistics = storage.copyWorldToTemporary(world, legacyTemporary);
        SnapshotMetadata legacyMetadata = new SnapshotMetadata(); legacyMetadata.snapshotId="legacy-id"; legacyMetadata.type="MANUAL"; legacyMetadata.slot=2; legacyMetadata.createdAtEpochMillis=legacyCreatedAt; legacyMetadata.customName="旧名称"; legacyMetadata.displayName=SnapshotNames.displayName(legacySlot, legacyCreatedAt, legacyMetadata.customName);
        legacyMetadata.fileCount=legacyStatistics.fileCount(); legacyMetadata.totalBytes=legacyStatistics.totalBytes();
        storage.finalizeTemporarySnapshot(legacyTemporary, legacyMetadata); storage.promoteSlotTemporary(legacyKey, legacySlot, legacyTemporary, legacyDestination);
        Files.delete(legacyDestination.resolve("checksums.sha256"));
        check(storage.readSlot(legacyKey, legacySlot) != null, "legacy slot receives integrity manifest");
        storage.migrateLegacySlots(legacyKey, "world-new-id");
        Path migratedLegacy = storage.slotDirectory("world-new-id", legacySlot);
        check(storage.readSlot("world-new-id", legacySlot) != null && !Files.exists(legacyDestination)
                && migratedLegacy.getFileName().toString().matches("manual_2-\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}"), "legacy slots migrate once to ASCII world identity directory");
        System.out.println("PASS: lock, replacement, recovery, isolation, slot validation, interrupted retry. Fixture: "+fixture);
    }
    private static void check(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
