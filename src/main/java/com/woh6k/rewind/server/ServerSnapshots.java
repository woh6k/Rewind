package com.woh6k.rewind.server;

import com.woh6k.rewind.snapshot.*;
import com.woh6k.rewind.network.RewindNetwork;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.*;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.*;
import java.util.*;

@Mod.EventBusSubscriber(modid="rewind")
public final class ServerSnapshots {
    private static ServerSnapshots active;
    public static volatile boolean integratedRestoreSucceeded;
    private final MinecraftServer server;
    private final SnapshotStorage storage=new SnapshotStorage();
    private final Path world;
    private final String key;
    private ServerSettings settings=new ServerSettings();
    private String state="IDLE";
    private long nextAuto, stopAt;
    private final Map<UUID,Challenge> challenges=new HashMap<>();
    private final Map<UUID,Long> requests=new HashMap<>();
    private final Map<UUID,Long> viewRequests=new HashMap<>();
    private final Map<UUID,PendingCommit> pendingCommits=new HashMap<>();
    private record Challenge(String token,SnapshotSlot slot,String snapshotId,long expires) {}
    private record PendingCommit(SnapshotSlot slot, String snapshotId, String customName, long expires) {}
    private ServerSnapshots(MinecraftServer server) throws Exception {
        this.server=server; world=server.getWorldPath(LevelResource.ROOT).toRealPath(); key=WorldIdentity.ensure(storage, world);
        storage.migrateLegacySlots(world.getFileName().toString(), key);
        storage.migrateLegacySlots(legacyPathKey(world), key);
        if(Files.exists(settingsPath())) settings=RewindNetwork.JSON.fromJson(Files.readString(settingsPath()),ServerSettings.class);
        settings.validate(); resetTimer();
    }
    /** Legacy path key is read-only fallback; active worlds receive a durable id in their own data directory. */
    public static String key(Path world) {
        Path idFile = world.toAbsolutePath().normalize().resolve("data").resolve("rewind-world-id.txt");
        try {
            if (Files.isRegularFile(idFile)) {
                return "world-" + readStoredWorldId(idFile);
            }
        } catch (Exception ignored) { }
        return legacyPathKey(world);
    }
    private static String legacyPathKey(Path world) {
        return world.getFileName()+"-"+UUID.nameUUIDFromBytes(world.toAbsolutePath().normalize().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static UUID readStoredWorldId(Path idFile) throws java.io.IOException {
        String raw = Files.readString(idFile).trim();
        // Repair the literal "\\n" emitted by the earlier build without changing the world's identity.
        if (raw.endsWith("\\n")) raw = raw.substring(0, raw.length() - 2).trim();
        return UUID.fromString(raw);
    }
    private Path settingsPath() { return storage.rootDirectory().resolve("worlds").resolve(key).resolve("settings.json"); }
    public static SnapshotSlot parseSlot(String value) {
        if(value==null || !value.matches("(auto_[1-3]|manual_[1-5])")) throw new IllegalArgumentException("Invalid slot");
        return value.startsWith("auto")?SnapshotSlot.automatic(Integer.parseInt(value.substring(5))):SnapshotSlot.manual(Integer.parseInt(value.substring(7)));
    }
    @SubscribeEvent public static void start(ServerStartedEvent e) {
        try {
            active=new ServerSnapshots(e.getServer());
            active.storage.cleanupAbandonedTemporaryDirectories();
        } catch(Exception ex) { throw new IllegalStateException("Cannot initialize Rewind server storage",ex); }
    }
    @SubscribeEvent public static void aboutToStart(ServerAboutToStartEvent e) {
        // On an in-session retry, common setup has already passed. Refuse loading until the pending restore is repaired.
        OfflineRestore.guardWorldOpening(e.getServer().getWorldPath(LevelResource.ROOT));
    }
    @SubscribeEvent public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("rewind")
                .requires(source -> source.getEntity() instanceof ServerPlayer player && allowed(source.getServer(), player))
                .then(Commands.literal("commit").then(Commands.argument("slot", IntegerArgumentType.integer(1, 5))
                        .executes(context -> commitCommand(context, false, null))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(context -> commitCommand(context, false, StringArgumentType.getString(context, "name"))))))
                .then(Commands.literal("sudo").then(Commands.literal("commit").then(Commands.argument("slot", IntegerArgumentType.integer(1, 5))
                        .executes(context -> commitCommand(context, true, null))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(context -> commitCommand(context, true, StringArgumentType.getString(context, "name")))))))
                .then(Commands.literal("Y").executes(ServerSnapshots::confirmCommand))
                .then(Commands.literal("y").executes(ServerSnapshots::confirmCommand))
                .then(Commands.literal("N").executes(ServerSnapshots::cancelCommand))
                .then(Commands.literal("n").executes(ServerSnapshots::cancelCommand)));
    }

    private static int commitCommand(CommandContext<CommandSourceStack> context, boolean sudo, String rawName) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        if (active == null || player.getServer() != active.server || !active.allowed(player)) {
            context.getSource().sendFailure(Component.literal("[还真] 当前没有可操作的世界，或你没有权限。"));
            return 0;
        }
        try {
            return active.requestCommandCommit(context.getSource(), player, SnapshotSlot.manual(IntegerArgumentType.getInteger(context, "slot")), SnapshotNames.normalizeCustomName(rawName), sudo);
        } catch (Exception error) {
            context.getSource().sendFailure(Component.literal("[还真] " + usefulMessage(error)));
            return 0;
        }
    }

    private static int confirmCommand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        if (active == null || player.getServer() != active.server || !active.allowed(player)) return 0;
        PendingCommit pending = active.pendingCommits.remove(player.getUUID());
        if (pending == null || pending.expires < System.currentTimeMillis()) {
            context.getSource().sendFailure(Component.literal("[还真] 没有等待确认的手动存档请求。"));
            return 0;
        }
        try {
            active.requireIdle();
            new SlotVersion(pending.slot, pending.snapshotId).requireCurrent(active.storage, active.key);
            active.save(pending.slot, pending.customName);
            return 1;
        } catch (Exception error) {
            context.getSource().sendFailure(Component.literal("[还真] " + usefulMessage(error)));
            return 0;
        }
    }

    private static int cancelCommand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        if (active == null || player.getServer() != active.server || active.pendingCommits.remove(player.getUUID()) == null) {
            context.getSource().sendFailure(Component.literal("[还真] 没有等待确认的手动存档请求。"));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("[还真] 已取消覆盖。"), false);
        return 1;
    }

    private int requestCommandCommit(CommandSourceStack source, ServerPlayer player, SnapshotSlot slot, String customName, boolean sudo) throws Exception {
        pendingCommits.remove(player.getUUID());
        requireIdle();
        // Validate the user-facing remark before flushing and pausing the world.
        SnapshotNames.directoryName(slot, System.currentTimeMillis(), customName);
        SnapshotInfo current = storage.readSlot(key, slot);
        if (current != null && !sudo) {
            pendingCommits.put(player.getUUID(), new PendingCommit(slot, current.metadata().snapshotId, customName, System.currentTimeMillis() + 60_000L));
            source.sendSuccess(() -> Component.literal("[还真] " + slot.id() + " 已有存档。输入 /rewind Y 确认覆盖，或 /rewind N 取消（60 秒内有效）。"), false);
            return 1;
        }
        save(slot, customName);
        return 1;
    }

    private void requireIdle() {
        if (!state.equals("IDLE")) throw new IllegalStateException("还真正在处理任务，请稍候。");
    }

    private static String usefulMessage(Exception error) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null ? error.getCause() : error;
        return cause.getMessage() == null ? "操作失败，请查看服务器日志。" : cause.getMessage();
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent e) {
        ServerSnapshots service=active; active=null;
        if(service!=null && !e.getServer().isDedicatedServer() && Files.exists(OfflineRestore.journal(service.storage,service.key))) {
            try { OfflineRestore.apply(service.storage,OfflineRestore.journal(service.storage,service.key)); integratedRestoreSucceeded=true; }
            catch(Exception ex) { com.mojang.logging.LogUtils.getLogger().error("Rewind offline restore failed; restart required to retry",ex); }
        }
    }
    private static boolean allowed(MinecraftServer server, ServerPlayer p) {
        if (!server.isDedicatedServer() && server.isSingleplayerOwner(p.getGameProfile())) return true;
        // LAN's "allow cheats for everyone" is not an administrator designation.
        var op = server.getPlayerList().getOps().get(p.getGameProfile());
        return op != null && op.getLevel() >= 4;
    }
    private boolean allowed(ServerPlayer p) { return allowed(server, p); }
    @SubscribeEvent public static void logout(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (active == null) return;
        UUID id = event.getEntity().getUUID();
        active.pendingCommits.remove(id); active.challenges.remove(id);
        active.requests.remove(id); active.viewRequests.remove(id);
    }
    public static void handle(ServerPlayer p,String action,String value) {
        if(p==null||active==null||p.getServer()!=active.server) return;
        ServerSnapshots s=active;
        long now=System.currentTimeMillis();
        if(action.equals("LIST")) {
            Long previous=s.viewRequests.put(p.getUUID(),now);
            if(previous==null||now-previous>=500) s.view(p);
            return;
        }
        try {
            Long last=s.requests.get(p.getUUID());
            if(last!=null&&now-last<100) throw new IllegalStateException("操作过快，请稍后重试。");
            s.requests.put(p.getUUID(),now);
            if(!s.allowed(p)) throw new IllegalStateException("只有 OP 4 级管理员或单人世界房主可以操作还真。");
            s.requireIdle();
            switch(action) {
                case "SAVE" -> {
                    SlotVersion version = SlotVersion.parse(value);
                    if (version.slot().type() != SnapshotType.MANUAL) throw new IllegalArgumentException("Manual only");
                    version.requireCurrent(s.storage, s.key);
                    s.save(version.slot(), null);
                }
                case "DELETE" -> {
                    SlotVersion version = SlotVersion.parse(value); version.requireCurrent(s.storage, s.key);
                    s.storage.deleteSlot(s.key, version.slot());
                }
                case "CLEANUP" -> {
                    if (!value.isEmpty()) throw new IllegalArgumentException("Invalid cleanup request");
                    int removed = s.storage.cleanupDisplaced(s.key);
                    p.sendSystemMessage(Component.literal("[还真] 已清理 " + removed + " 份回档旧世界副本；最近一次回档安全备份仍保留。"));
                }
                case "DEFAULT_SAVE" -> { SnapshotSlot slot=parseSlot(value); if(slot.type()!=SnapshotType.MANUAL) throw new IllegalArgumentException("Manual only"); s.settings.defaultManual=slot.number(); }
                case "DEFAULT_LOAD" -> s.settings.defaultRestore=parseSlot(value).id();
                case "AUTO" -> { if(!value.equals("true")&&!value.equals("false")) throw new IllegalArgumentException(); s.settings.automatic=Boolean.parseBoolean(value); s.resetTimer(); }
                case "INTERVAL" -> { int minutes=Integer.parseInt(value); if(minutes<1||minutes>1440) throw new IllegalArgumentException("1–1440 分钟"); s.settings.intervalMinutes=minutes; s.resetTimer(); }
                case "PREPARE" -> {
                    SnapshotSlot slot=parseSlot(value); SnapshotInfo info=s.storage.readSlot(s.key,slot); if(info==null) throw new IllegalStateException("存档不存在或不完整");
                    String token=UUID.randomUUID().toString(); s.challenges.put(p.getUUID(),new Challenge(token,slot,info.metadata().snapshotId,now+60000));
                    RewindNetwork.send(p,"CONFIRM",slot.id()+":"+token);
                }
                case "CONFIRM" -> {
                    Challenge c=s.challenges.remove(p.getUUID()); if(c==null||!c.token.equals(value)||c.expires<now) throw new IllegalStateException("确认已过期，请重新选择存档。");
                    SnapshotInfo info=s.storage.readSlot(s.key,c.slot); if(info==null||!info.metadata().snapshotId.equals(c.snapshotId)) throw new IllegalStateException("存档已变化，请重新确认。");
                    OfflineRestore.write(OfflineRestore.journal(s.storage,s.key),new OfflineRestore.Plan(s.world.toString(),s.key,c.slot.id(),c.snapshotId,"QUEUED"));
                    integratedRestoreSucceeded=false; s.state="CLOSING_WORLD"; s.stopAt=now+5000;
                    s.server.getPlayerList().broadcastSystemMessage(Component.literal("[还真] 管理员已确认回档，5 秒后关闭世界。无需确认，服务器重启后请重新连接。"),false);
                    for(ServerPlayer player:s.server.getPlayerList().getPlayers()) RewindNetwork.send(player,"NOTICE",s.server.isDedicatedServer()?"remote":s.world.getFileName().toString());
                    com.mojang.logging.LogUtils.getLogger().info("Rewind restore approved by {} ({}) for {} snapshot {}",p.getGameProfile().getName(),p.getUUID(),s.key,c.snapshotId);
                }
                default -> throw new IllegalArgumentException("Unknown action");
            }
            OfflineRestore.write(s.settingsPath(),s.settings); s.view(p);
        } catch(Exception ex) { com.mojang.logging.LogUtils.getLogger().warn("Rewind request {} failed",action,ex); p.sendSystemMessage(Component.literal("[还真] "+ex.getMessage())); s.view(p); }
        finally {
            if (action.equals("INTERVAL")) RewindNetwork.send(p, "INTERVAL_ACK", value + ":" + s.settings.intervalMinutes);
        }
    }
    private void view(ServerPlayer p) { boolean admin=allowed(p); RewindNetwork.send(p,"VIEW",RewindNetwork.JSON.toJson(new ServerView(admin,server.getWorldData().getLevelName(),state,settings,admin?storage.listSnapshots(key).stream().map(SnapshotInfo::metadata).toList():List.of()))); }
    private void resetTimer() { nextAuto=System.currentTimeMillis()+settings.intervalMinutes*60000L; }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent e) {
        if(e.phase!=TickEvent.Phase.END||active==null) return;
        ServerSnapshots s=active; long now=System.currentTimeMillis();
        if(s.stopAt>0&&now>=s.stopAt) { s.stopAt=0; s.server.halt(false); return; }
        if(!s.state.equals("IDLE")||!s.settings.automatic||now<s.nextAuto) return;
        s.resetTimer();
        try {
            List<SnapshotInfo> existing=s.storage.listSnapshots(s.key).stream().filter(i->i.slot().type()==SnapshotType.AUTO).toList();
            SnapshotSlot slot=null; for(int i=1;i<=3;i++) { SnapshotSlot candidate=SnapshotSlot.automatic(i); if(existing.stream().noneMatch(v->v.slot().equals(candidate))) { slot=candidate; break; } }
            if(slot==null) slot=existing.stream().min(Comparator.comparingLong(i->i.metadata().createdAtEpochMillis)).orElseThrow().slot();
            s.save(slot, null);
        } catch(Exception ex) { com.mojang.logging.LogUtils.getLogger().error("Automatic snapshot failed",ex); }
    }
    private void save(SnapshotSlot slot, String customName) throws Exception {
        if(server instanceof net.minecraft.server.dedicated.DedicatedServer dedicated && dedicated.getMaxTickLength()>0 && dedicated.getMaxTickLength()<40000)
            throw new IllegalStateException("服务端 max-tick-time 小于 40000 ms，无法安全使用当前快照保存屏障。");
        // Copy and checksum are a single protected operation; leave headroom before the usual watchdog limit.
        long deadline=System.nanoTime()+20_000_000_000L;
        state="SAVING_WORLD";
        try {
            server.saveEverything(true,true,true);
            long createdAt = System.currentTimeMillis();
            SnapshotMetadata meta=new SnapshotMetadata(); meta.snapshotId=UUID.randomUUID().toString(); meta.type=slot.type().name(); meta.slot=slot.number(); meta.worldName=server.getWorldData().getLevelName(); meta.gameTime=server.overworld().getGameTime(); meta.createdAtEpochMillis=createdAt; meta.minecraftVersion="1.20.1"; meta.customName=customName; meta.displayName=SnapshotNames.displayName(slot, createdAt, customName);
            Path dest=storage.newSlotDirectory(key,slot,SnapshotNames.directoryName(slot, createdAt, customName)), temp=storage.createTemporaryDirectory(dest);
            // Keep the server at its save barrier until the file worker finishes; LAN clients cannot resume ticking it.
            java.util.concurrent.CompletableFuture.runAsync(()->{
                try { var stats=storage.copyWorldToTemporary(world,temp,deadline); meta.fileCount=stats.fileCount(); meta.totalBytes=stats.totalBytes(); storage.finalizeTemporarySnapshot(temp,meta); storage.promoteSlotTemporary(key,slot,temp,dest); }
                catch(Exception ex) { throw new java.util.concurrent.CompletionException(ex); }
            }).join();
            server.getPlayerList().broadcastSystemMessage(Component.literal("[还真] 已保存 "+meta.displayName),false);
        } finally { state="IDLE"; }
    }

}
