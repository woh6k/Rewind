package com.woh6k.rewind.snapshot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.woh6k.rewind.Rewind;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * File-system-only snapshot repository. It deliberately has no Minecraft world references.
 * Callers must prove that a source directory is stable before invoking copyWorldToTemporary.
 */
public final class SnapshotStorage {
    private final Path root;
    public SnapshotStorage() { this(FMLPaths.GAMEDIR.get().resolve(Rewind.MOD_ID)); }
    public SnapshotStorage(Path root) { this.root = root.toAbsolutePath().normalize(); }
    public static final String COMPLETE_MARKER = "COMPLETE";
    private static final String METADATA_FILE = "snapshot.json";
    private static final String MANIFEST_FILE = "checksums.sha256";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public Path rootDirectory() {
        return root;
    }

    public Path slotDirectory(String worldKey, SnapshotSlot slot) {
        checkKey(worldKey);
        Path category = categoryDirectory(worldKey, slot);
        Path legacy = legacySlotDirectory(worldKey, slot);
        if (basicSnapshot(legacy)) return legacy;
        if (!Files.isDirectory(category)) return legacy;
        try (var entries = Files.list(category)) {
            return entries.filter(Files::isDirectory)
                    .filter(SnapshotStorage::isActiveSnapshotDirectory)
                    .filter(SnapshotStorage::basicSnapshot)
                    .filter(path -> matchesSlot(path, slot))
                    .findFirst().orElse(legacy);
        } catch (IOException error) {
            LOGGER.warn("Unable to locate Rewind snapshot slot {}", slot.id(), error);
            return legacy;
        }
    }

    /** Disk names contain the slot and ASCII timestamp; display names live in metadata. */
    public Path newSlotDirectory(String worldKey, SnapshotSlot slot, String directoryName) {
        checkKey(worldKey);
        if (directoryName == null || directoryName.isBlank() || directoryName.contains("/") || directoryName.contains("\\"))
            throw new IllegalArgumentException("Invalid snapshot directory name");
        return categoryDirectory(worldKey, slot).resolve(directoryName);
    }

    private Path categoryDirectory(String worldKey, SnapshotSlot slot) {
        String category = slot.type() == SnapshotType.AUTO ? "auto" : "manual";
        return rootDirectory().resolve("worlds").resolve(worldKey).resolve(category);
    }

    private Path legacySlotDirectory(String worldKey, SnapshotSlot slot) {
        return categoryDirectory(worldKey, slot).resolve("slot_" + slot.number());
    }

    public Path recoveryDirectory(String worldKey) {
        checkKey(worldKey);
        return rootDirectory().resolve("worlds").resolve(worldKey).resolve("recovery").resolve("last_restore_backup");
    }

    /** Moves only validated, legacy mod-owned slots; it never touches a Minecraft world directory. */
    public void migrateLegacySlots(String legacyKey, String worldKey) throws IOException {
        if (legacyKey.equals(worldKey)) return;
        cleanupLegacyDeletedSnapshots(legacyKey);
        for (SnapshotSlot slot : allSlots()) {
            SnapshotInfo old = readSlot(legacyKey, slot);
            SnapshotInfo current = readSlot(worldKey, slot);
            if (old != null && current == null) {
                Path target = newSlotDirectory(worldKey, slot,
                        SnapshotNames.directoryName(slot, old.metadata().createdAtEpochMillis, null));
                Files.createDirectories(target.getParent());
                moveDirectory(old.directory(), target);
            }
        }
        Path oldSettings = rootDirectory().resolve("worlds").resolve(legacyKey).resolve("settings.json");
        Path targetSettings = rootDirectory().resolve("worlds").resolve(worldKey).resolve("settings.json");
        if (Files.isRegularFile(oldSettings) && !Files.exists(targetSettings)) {
            Files.createDirectories(targetSettings.getParent()); Files.move(oldSettings, targetSettings);
        }
        Path oldRecovery = recoveryDirectory(legacyKey), targetRecovery = recoveryDirectory(worldKey);
        if (basicSnapshot(oldRecovery) && !Files.exists(targetRecovery)) {
            if (!Files.isRegularFile(oldRecovery.resolve(MANIFEST_FILE))) upgradeLegacySnapshot(oldRecovery);
            Files.createDirectories(targetRecovery.getParent()); moveDirectory(oldRecovery, targetRecovery);
        }
        removeEmptyDirectories(rootDirectory().resolve("worlds").resolve(legacyKey));
    }

    /** Old GUI deletes used a visible .deleted-* directory; the current GUI is hard-delete. */
    private void cleanupLegacyDeletedSnapshots(String worldKey) throws IOException {
        for (String category : List.of("auto", "manual")) {
            Path directory = rootDirectory().resolve("worlds").resolve(worldKey).resolve(category);
            if (!Files.isDirectory(directory)) continue;
            try (var entries = Files.list(directory)) {
                for (Path entry : entries.filter(Files::isDirectory)
                        .filter(path -> path.getFileName().toString().contains(".deleted-")).toList()) {
                    deleteRecursively(entry);
                }
            }
        }
    }

    private static void removeEmptyDirectories(Path worldDirectory) throws IOException {
        for (String child : List.of("auto", "manual", "recovery")) {
            Path candidate = worldDirectory.resolve(child);
            if (isEmptyDirectory(candidate)) Files.delete(candidate);
        }
        if (isEmptyDirectory(worldDirectory)) Files.delete(worldDirectory);
    }

    private static boolean isEmptyDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return false;
        try (var entries = Files.list(directory)) { return entries.findAny().isEmpty(); }
    }

    public boolean isComplete(Path directory) {
        return basicSnapshot(directory)
                && Files.isRegularFile(directory.resolve(MANIFEST_FILE));
    }

    private static boolean basicSnapshot(Path directory) {
        return Files.isDirectory(directory)
                && Files.isRegularFile(directory.resolve(METADATA_FILE))
                && Files.isRegularFile(directory.resolve(COMPLETE_MARKER))
                && Files.isRegularFile(directory.resolve("level.dat"));
    }

    private static boolean isActiveSnapshotDirectory(Path directory) {
        String name = directory.getFileName().toString();
        return !name.contains(".tmp-") && !name.contains(".previous-") && !name.contains(".deleted-");
    }

    public SnapshotInfo readSlot(String worldKey, SnapshotSlot slot) throws IOException {
        Path directory = slotDirectory(worldKey, slot);
        if (!basicSnapshot(directory)) {
            return null;
        }
        rejectLinks(directory);
        if (!Files.isRegularFile(directory.resolve(MANIFEST_FILE))) upgradeLegacySnapshot(directory);
        try (Reader reader = Files.newBufferedReader(directory.resolve(METADATA_FILE), StandardCharsets.UTF_8)) {
            SnapshotMetadata metadata = GSON.fromJson(reader, SnapshotMetadata.class);
            if (metadata == null || !"COMPLETE".equals(metadata.state) || metadata.snapshotId==null
                    || !slot.type().name().equals(metadata.type) || slot.number()!=metadata.slot) {
                return null;
            }
            return new SnapshotInfo(slot, metadata, directory);
        }
    }

    private boolean matchesSlot(Path directory, SnapshotSlot slot) {
        try (Reader reader = Files.newBufferedReader(directory.resolve(METADATA_FILE), StandardCharsets.UTF_8)) {
            SnapshotMetadata metadata = GSON.fromJson(reader, SnapshotMetadata.class);
            return metadata != null && slot.type().name().equals(metadata.type) && slot.number() == metadata.slot
                    && "COMPLETE".equals(metadata.state);
        } catch (IOException | RuntimeException ignored) { return false; }
    }

    public List<SnapshotInfo> listSnapshots(String worldKey) {
        List<SnapshotInfo> snapshots = new ArrayList<>();
        for (int slot = 1; slot <= 3; slot++) {
            addIfPresent(snapshots, worldKey, SnapshotSlot.automatic(slot));
        }
        for (int slot = 1; slot <= 5; slot++) {
            addIfPresent(snapshots, worldKey, SnapshotSlot.manual(slot));
        }
        return snapshots;
    }

    public Path createTemporaryDirectory(Path destination) throws IOException {
        destination = destination.toAbsolutePath().normalize();
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.createDirectories(temporary);
        return temporary;
    }

    /** Performs the expensive checksum pass only immediately before a restore. */
    public void verifySnapshot(Path directory) throws IOException {
        if (!isComplete(directory)) throw new IOException("Snapshot is incomplete: " + directory);
        rejectLinks(directory);
        long files = 0, bytes = 0;
        java.util.Set<Path> listed = new java.util.HashSet<>();
        for (String line : Files.readAllLines(directory.resolve(MANIFEST_FILE), StandardCharsets.UTF_8)) {
            String[] parts = line.split("\\t", 3);
            if (parts.length != 3) throw new IOException("Invalid snapshot checksum manifest");
            long size;
            Path relative;
            try { size = Long.parseLong(parts[1]); relative = Path.of(new String(Base64.getUrlDecoder().decode(parts[2]), StandardCharsets.UTF_8)); }
            catch (RuntimeException ex) { throw new IOException("Invalid snapshot checksum manifest", ex); }
            if (relative.isAbsolute() || relative.normalize().startsWith("..")) throw new IOException("Unsafe snapshot manifest path");
            Path file = directory.resolve(relative).normalize();
            if (isGeneratedRootFile(relative) || !listed.add(file)) throw new IOException("Duplicate or reserved manifest entry");
            rejectLinks(file);
            if (!file.startsWith(directory) || !Files.isRegularFile(file) || Files.isSymbolicLink(file)) throw new IOException("Snapshot file missing: " + relative);
            if (Files.size(file) != size || !parts[0].equals(sha256(file, Long.MAX_VALUE))) throw new IOException("Snapshot checksum mismatch: " + relative);
            files++; bytes += size;
        }
        try (var actual = Files.walk(directory)) {
            for (Path path : actual.toList()) {
                rejectLinks(path);
                if (Files.isDirectory(path)) continue;
                if (!Files.isRegularFile(path)) throw new IOException("Unsupported snapshot entry: " + path);
                if (!isGeneratedRootFile(directory.relativize(path)) && !listed.contains(path.normalize()))
                    throw new IOException("Snapshot contains unlisted file: " + directory.relativize(path));
            }
        }
        if (files == 0 || !Files.isRegularFile(directory.resolve("level.dat"))) throw new IOException("Snapshot manifest has no world files");
        try (Reader reader = Files.newBufferedReader(directory.resolve(METADATA_FILE), StandardCharsets.UTF_8)) {
            SnapshotMetadata metadata = GSON.fromJson(reader, SnapshotMetadata.class);
            if (metadata == null || metadata.fileCount != files || metadata.totalBytes != bytes)
                throw new IOException("Snapshot metadata does not match its manifest");
        }
    }

    /** One-time in-place upgrade for slots created before checksum manifests existed. */
    private void upgradeLegacySnapshot(Path directory) throws IOException {
        LOGGER.info("Upgrading legacy Rewind snapshot integrity manifest at {}", directory);
        CopyStatistics statistics = writeManifest(directory, Long.MAX_VALUE);
        try (Reader reader = Files.newBufferedReader(directory.resolve(METADATA_FILE), StandardCharsets.UTF_8)) {
            SnapshotMetadata metadata = GSON.fromJson(reader, SnapshotMetadata.class);
            if (metadata == null) throw new IOException("Legacy snapshot metadata is unreadable");
            metadata.fileCount = statistics.fileCount();
            metadata.totalBytes = statistics.totalBytes();
            try (Writer writer = Files.newBufferedWriter(directory.resolve(METADATA_FILE), StandardCharsets.UTF_8)) { GSON.toJson(metadata, writer); }
        }
    }

    /** Copies every ordinary world file except the live session lock. */
    public CopyStatistics copyWorldToTemporary(Path source, Path temporary) throws IOException {
        return copyWorldToTemporary(source, temporary, Long.MAX_VALUE);
    }

    /** The deadline bounds the server snapshot barrier; incomplete copies are never promoted. */
    public CopyStatistics copyWorldToTemporary(Path source, Path temporary, long deadlineNanos) throws IOException {
        Path sourceRoot = source.toAbsolutePath().normalize();
        Path targetRoot = temporary.toAbsolutePath().normalize();
        if (sourceRoot.startsWith(targetRoot) || targetRoot.startsWith(sourceRoot))
            throw new IOException("Snapshot source and destination overlap");
        rejectLinks(sourceRoot);
        rejectLinks(targetRoot);
        if (!Files.isDirectory(source)) {
            throw new IOException("World source is not a directory: " + source);
        }
        CopyStatistics statistics = new CopyStatistics();
        Path manifest = temporary.resolve(MANIFEST_FILE);
        try (Writer manifestWriter = Files.newBufferedWriter(manifest, StandardCharsets.UTF_8)) {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (System.nanoTime() > deadlineNanos) throw new IOException("Snapshot exceeded copy time budget");
                rejectLinks(directory);
                Files.createDirectories(temporary.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (System.nanoTime() > deadlineNanos) throw new IOException("Snapshot exceeded copy time budget");
                if (!attributes.isRegularFile() || Files.isSymbolicLink(file))
                    throw new IOException("Unsupported world file: " + file);
                Path relative = source.relativize(file);
                if (isGeneratedRootFile(relative)) {
                    return FileVisitResult.CONTINUE;
                }
                try (var input=Files.newInputStream(file); var output=Files.newOutputStream(temporary.resolve(relative), java.nio.file.StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer=new byte[1024*1024]; int length;
                    while((length=input.read(buffer))!=-1) {
                        if(System.nanoTime()>deadlineNanos) throw new IOException("Snapshot exceeded copy time budget");
                        output.write(buffer,0,length);
                    }
                }
                manifestWriter.write(sha256(temporary.resolve(relative), deadlineNanos));
                manifestWriter.write("\t" + attributes.size() + "\t" + Base64.getUrlEncoder().withoutPadding().encodeToString(relative.toString().getBytes(StandardCharsets.UTF_8)) + "\n");
                statistics.fileCount++;
                statistics.totalBytes += attributes.size();
                return FileVisitResult.CONTINUE;
            }
        });
        }
        return statistics;
    }

    /** Writes metadata and a marker only after the world copy exists and has a level.dat. */
    public void finalizeTemporarySnapshot(Path temporary, SnapshotMetadata metadata) throws IOException {
        if (!Files.isRegularFile(temporary.resolve("level.dat"))) {
            throw new IOException("Copied snapshot has no level.dat: " + temporary);
        }
        metadata.state = "COMPLETE";
        try (Writer writer = Files.newBufferedWriter(temporary.resolve(METADATA_FILE), StandardCharsets.UTF_8)) {
            GSON.toJson(metadata, writer);
        }
        Files.writeString(temporary.resolve(COMPLETE_MARKER), "Rewind snapshot complete\n", StandardCharsets.UTF_8);
    }

    /**
     * Replaces a completed slot without deleting its old version until the new directory is ready.
     * The old directory is restored if the final move fails.
     */
    public void promoteTemporary(Path temporary, Path destination) throws IOException {
        if (!isComplete(temporary)) {
            throw new IOException("Refusing to promote an incomplete snapshot: " + temporary);
        }
        Files.createDirectories(destination.getParent());
        Path backup = destination.resolveSibling(destination.getFileName() + ".previous-" + UUID.randomUUID());
        boolean oldMoved = false;
        try {
            if (Files.exists(destination)) {
                moveDirectory(destination, backup);
                oldMoved = true;
            }
            moveDirectory(temporary, destination);
            if (oldMoved) {
                try { deleteRecursively(backup); }
                catch (IOException cleanup) { LOGGER.warn("Snapshot committed; retained old copy {}", backup, cleanup); }
            }
        } catch (IOException failure) {
            if (!Files.exists(destination) && oldMoved && Files.exists(backup)) {
                try {
                    moveDirectory(backup, destination);
                } catch (IOException restoreFailure) {
                    failure.addSuppressed(restoreFailure);
                }
            }
            throw failure;
        }
    }

    /** Atomically switches a logical slot even when the new readable directory name changed. */
    public void promoteSlotTemporary(String worldKey, SnapshotSlot slot, Path temporary, Path destination) throws IOException {
        Path existing = slotDirectory(worldKey, slot);
        if (!Files.exists(existing) || existing.equals(destination)) {
            promoteTemporary(temporary, destination);
            return;
        }
        Path retired = existing.resolveSibling(existing.getFileName() + ".previous-" + UUID.randomUUID());
        moveDirectory(existing, retired);
        try {
            promoteTemporary(temporary, destination);
            try { deleteRecursively(retired); }
            catch (IOException cleanup) { LOGGER.warn("Snapshot committed; retained old copy {}", retired, cleanup); }
        } catch (IOException failure) {
            if (!Files.exists(existing) && Files.exists(retired)) {
                try { moveDirectory(retired, existing); }
                catch (IOException rollback) { failure.addSuppressed(rollback); }
            }
            throw failure;
        }
    }

    public void deleteSlot(String worldKey, SnapshotSlot slot) throws IOException {
        deleteRecursively(slotDirectory(worldKey, slot));
    }

    /** Manually remove only completed-restore displaced copies for one world. */
    public int cleanupDisplaced(String worldKey) throws IOException {
        checkKey(worldKey);
        Path pending = rootDirectory().resolve("pending").resolve(worldKey + ".json");
        if (Files.exists(pending)) throw new IOException("回档任务尚未完成，不能清理旧世界副本。");
        Path recovery = rootDirectory().resolve("worlds").resolve(worldKey).resolve("recovery").toAbsolutePath().normalize();
        if (!Files.isDirectory(recovery)) return 0;
        rejectLinks(recovery);
        List<Path> candidates;
        try (var entries = Files.list(recovery)) {
            candidates = entries.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().matches("displaced-[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))
                    .toList();
        }
        // Validate every candidate before deleting the first one. Never traverse a junction or link.
        for (Path candidate : candidates) {
            if (!candidate.toAbsolutePath().normalize().getParent().equals(recovery))
                throw new IOException("Unsafe Rewind cleanup path");
            try (var paths = Files.walk(candidate)) {
                for (Path path : paths.toList()) rejectLinks(path);
            }
        }
        if (Files.exists(pending)) throw new IOException("回档任务已开始，清理已取消。");
        for (Path candidate : candidates) deleteRecursively(candidate);
        return candidates.size();
    }

    public void cleanupAbandonedTemporaryDirectories() {
        try {
            Path worlds = rootDirectory().resolve("worlds");
            if (!Files.isDirectory(worlds)) {
                return;
            }
            try (var paths = Files.walk(worlds, 5)) {
                paths.filter(Files::isDirectory)
                        .filter(path -> path.getFileName().toString().contains(".tmp-"))
                        .forEach(path -> {
                            try {
                                deleteRecursively(path);
                            } catch (IOException exception) {
                                LOGGER.warn("Unable to remove abandoned Rewind temporary directory {}", path, exception);
                            }
                        });
            }
        } catch (IOException exception) {
            LOGGER.warn("Unable to scan Rewind temporary directories", exception);
        }
    }

    public static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void addIfPresent(List<SnapshotInfo> snapshots, String worldKey, SnapshotSlot slot) {
        try {
            SnapshotInfo snapshot = readSlot(worldKey, slot);
            if (snapshot != null) {
                snapshots.add(snapshot);
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Unable to read Rewind snapshot {}", slot.id(), exception);
        }
    }

    private static void checkKey(String key) {
        if (key == null || key.isBlank() || key.equals(".") || key.equals("..")
                || key.contains("/") || key.contains("\\") || key.contains(":"))
            throw new IllegalArgumentException("Invalid world key");
    }

    public static void rejectLinks(Path path) throws IOException {
        for (Path cursor = path.toAbsolutePath().normalize(); cursor != null; cursor = cursor.getParent())
            if (Files.isSymbolicLink(cursor) || Files.readAttributes(cursor, BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS).isOther())
                throw new IOException("Linked or special path is not supported: " + cursor);
    }

    private static void moveDirectory(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(from, to);
        }
    }

    private static String sha256(Path file, long deadlineNanos) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[1024 * 1024]; int length;
                while ((length = input.read(buffer)) != -1) {
                    if (System.nanoTime() > deadlineNanos) throw new IOException("Snapshot exceeded checksum time budget");
                    digest.update(buffer, 0, length);
                }
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IOException("SHA-256 is unavailable", impossible); }
    }

    private CopyStatistics writeManifest(Path directory, long deadlineNanos) throws IOException {
        Path replacement = directory.resolveSibling(directory.getFileName() + ".manifest-" + UUID.randomUUID());
        CopyStatistics statistics = new CopyStatistics();
        try (Writer writer = Files.newBufferedWriter(replacement, StandardCharsets.UTF_8)) {
            try (var files = Files.walk(directory)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    Path relative = directory.relativize(file);
                    if (isGeneratedRootFile(relative)) continue;
                    long size = Files.size(file);
                    writer.write(sha256(file, deadlineNanos));
                    writer.write("\t" + size + "\t" + Base64.getUrlEncoder().withoutPadding().encodeToString(relative.toString().getBytes(StandardCharsets.UTF_8)) + "\n");
                    statistics.fileCount++; statistics.totalBytes += size;
                }
            }
        } catch (Throwable error) { Files.deleteIfExists(replacement); throw error; }
        try { Files.move(replacement, directory.resolve(MANIFEST_FILE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(replacement, directory.resolve(MANIFEST_FILE), StandardCopyOption.REPLACE_EXISTING); }
        return statistics;
    }

    private static boolean isGeneratedRootFile(Path relative) {
        if (relative.getNameCount() != 1) return false;
        String name = relative.toString();
        return name.equals("session.lock") || name.equals(METADATA_FILE) || name.equals(COMPLETE_MARKER) || name.equals(MANIFEST_FILE);
    }

    private static List<SnapshotSlot> allSlots() {
        List<SnapshotSlot> slots = new ArrayList<>();
        for (int i = 1; i <= 3; i++) slots.add(SnapshotSlot.automatic(i));
        for (int i = 1; i <= 5; i++) slots.add(SnapshotSlot.manual(i));
        return slots;
    }

    public static final class CopyStatistics {
        private long fileCount;
        private long totalBytes;

        public long fileCount() {
            return fileCount;
        }

        public long totalBytes() {
            return totalBytes;
        }
    }
}
