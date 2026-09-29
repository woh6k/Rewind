package com.woh6k.rewind.server;

import com.google.gson.Gson;
import com.woh6k.rewind.snapshot.SnapshotStorage;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

/** Records ownership outside snapshots so copying a world cannot silently reuse its slots. */
public final class WorldIdentity {
    private record Binding(String world) {}
    private static Path binding(SnapshotStorage storage, String key) {
        storage.recoveryDirectory(key); // Validate key before resolving a path.
        return storage.rootDirectory().resolve("worlds").resolve(key).resolve("identity.json");
    }
    public static String read(Path world) throws IOException {
        Path file = world.resolve("data/rewind-world-id.txt");
        if (!Files.exists(file)) return null;
        SnapshotStorage.rejectLinks(file);
        String raw = Files.readString(file).trim();
        if (raw.endsWith("\\n")) raw = raw.substring(0, raw.length()-2).trim();
        try { return "world-" + UUID.fromString(raw); }
        catch (IllegalArgumentException ex) { throw new IOException("Invalid Rewind world identity", ex); }
    }
    public static synchronized String ensure(SnapshotStorage storage, Path world) throws IOException {
        world = world.toRealPath();
        String key = read(world);
        if (key == null) key = "world-" + UUID.randomUUID();
        Path previous = owner(storage, key);
        if (previous != null && !previous.equals(world)) {
            // An existing old path means this is a copy, not a move.
            // Unfinished restores must retain ownership even if their directory vanished.
            if (Files.exists(previous) || Files.exists(OfflineRestore.journal(storage, key)))
                key = "world-" + UUID.randomUUID();
        }
        Path id = world.resolve("data/rewind-world-id.txt");
        Files.createDirectories(id.getParent());
        String wanted = key.substring(6) + "\n";
        if (!Files.exists(id) || !Files.readString(id).equals(wanted)) {
            Path temp = Files.createTempFile(id.getParent(), "rewind-id-", ".new");
            try {
                Files.writeString(temp, wanted);
                try (var channel = java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
                Files.move(temp, id, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temp); }
        }
        OfflineRestore.write(binding(storage, key), new Binding(world.toString()));
        return key;
    }
    public static Path owner(SnapshotStorage storage, String key) throws IOException {
        Path file = binding(storage, key);
        if (!Files.exists(file)) return null;
        SnapshotStorage.rejectLinks(file);
        try {
            Binding value = new Gson().fromJson(Files.readString(file), Binding.class);
            if (value == null || value.world == null) throw new IOException("Missing world binding");
            return Path.of(value.world).toAbsolutePath().normalize();
        } catch (RuntimeException ex) { throw new IOException("Invalid world binding", ex); }
    }
}
