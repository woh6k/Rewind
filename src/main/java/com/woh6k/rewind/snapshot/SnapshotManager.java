package com.woh6k.rewind.snapshot;
import com.woh6k.rewind.network.RewindNetwork;
import com.woh6k.rewind.server.*;
import com.woh6k.rewind.client.screen.*;
import com.woh6k.rewind.state.RewindState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import java.util.List;
/** Client cache only; never reads or writes snapshot files. */
public final class SnapshotManager {
    private static final SnapshotManager INSTANCE=new SnapshotManager();
    private ServerView view;
    private Object connection;
    private int ticks;
    private String reopen;
    private IntegratedServer closing;
    private Integer pendingInterval;
    private long intervalSentAt;
    public static SnapshotManager get() { return INSTANCE; }
    public ServerSettings settings() { return view==null?new ServerSettings():view.settings(); }
    public boolean administrator() { return view!=null&&view.administrator(); }
    public boolean isBusy() { return !administrator()||!view.state().equals("IDLE"); }
    public RewindState state() { return view==null?RewindState.IDLE:RewindState.valueOf(view.state()); }
    public List<SnapshotInfo> snapshots() { return view==null?List.of():view.snapshots().stream().map(m->new SnapshotInfo(new SnapshotSlot(SnapshotType.valueOf(m.type),m.slot),m,null)).toList(); }
    private void send(String action,String value) { if(Minecraft.getInstance().getConnection()!=null) RewindNetwork.CHANNEL.sendToServer(new RewindNetwork.Request(action,value)); }
    public void requestQuickSnapshot() { requestSnapshot(SnapshotSlot.manual(settings().defaultManual)); }
    public String snapshotVersion(SnapshotSlot slot) {
        return snapshots().stream().filter(info -> info.slot().equals(slot)).map(info -> info.metadata().snapshotId).findFirst().orElse("-");
    }
    public void requestSnapshot(SnapshotSlot slot) { requestSnapshot(slot, snapshotVersion(slot)); }
    public void requestSnapshot(SnapshotSlot slot, String expectedId) { send("SAVE",slot.id() + ":" + expectedId); }
    public void deleteSnapshot(SnapshotSlot slot, String expectedId) { send("DELETE",slot.id() + ":" + expectedId); }
    public void cleanupRestoreCache() { send("CLEANUP", ""); }
    public void setDefaultManualSlot(SnapshotSlot slot) { send("DEFAULT_SAVE",slot.id()); }
    public void setDefaultRestoreSlot(SnapshotSlot slot) { send("DEFAULT_LOAD",slot.id()); }
    public void requestRestore(SnapshotSlot slot) { send("PREPARE",slot.id()); }
    public void confirmRestore(String token) { send("CONFIRM",token); Minecraft.getInstance().setScreen(null); }
    public void setAutomatic(boolean enabled) { send("AUTO",Boolean.toString(enabled)); }
    public int intervalMinutes() {
        if (pendingInterval != null && net.minecraft.Util.getMillis() - intervalSentAt > 3000) pendingInterval = null;
        return pendingInterval == null ? settings().intervalMinutes : pendingInterval;
    }
    public void setInterval(int minutes) {
        pendingInterval = minutes; intervalSentAt = net.minecraft.Util.getMillis();
        send("INTERVAL",Integer.toString(minutes));
    }
    public void receive(String kind,String value) {
        Minecraft mc=Minecraft.getInstance();
        switch(kind) {
            case "VIEW" -> view=RewindNetwork.JSON.fromJson(value,ServerView.class);
            case "INTERVAL_ACK" -> {
                String[] parts = value.split(":", 2);
                if (pendingInterval != null && parts.length == 2 && parts[0].equals(pendingInterval.toString())) {
                    if (view != null) view.settings().intervalMinutes = Integer.parseInt(parts[1]);
                    pendingInterval = null;
                }
            }
            case "CONFIRM" -> { String[] parts=value.split(":",2); mc.setScreen(new RestoreConfirmScreen(mc.screen,ServerSnapshots.parseSlot(parts[0]),parts[1])); }
            case "NOTICE" -> { if(mc.getSingleplayerServer()!=null&&!value.equals("remote")) { closing=mc.getSingleplayerServer(); reopen=value; } }
            default -> { }
        }
    }
    public void tickAutomaticSnapshots() {
        Minecraft mc=Minecraft.getInstance();
        if(connection!=mc.getConnection()) { connection=mc.getConnection(); view=null; pendingInterval=null; ticks=19; }
        if(++ticks>=20) { ticks=0; if(mc.player!=null) send("LIST",""); }
        if(closing!=null&&!closing.getRunningThread().isAlive()) {
            closing=null; String world=reopen; reopen=null;
            if(ServerSnapshots.integratedRestoreSucceeded) {
                RewindTransitionScreen transition=new RewindTransitionScreen(Component.translatable("screen.rewind.restore.loading"));
                mc.clearLevel(transition); mc.createWorldOpenFlows().loadLevel(transition,world);
            } else mc.setScreen(new net.minecraft.client.gui.screens.DisconnectedScreen(new net.minecraft.client.gui.screens.TitleScreen(),Component.literal("还真回档未完成"),Component.literal("已保留回档任务及备份，请查看日志，修复后重启游戏重试。")));
        }
    }
}
