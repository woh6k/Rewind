package com.woh6k.rewind.client.screen;

import com.woh6k.rewind.snapshot.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import java.text.DateFormat;
import java.util.*;

/** Height-aware cards with fixed navigation, cached metadata and wheel paging. */
public final class RewindManagerScreen extends Screen {
    private final Screen parent;
    private SnapshotType category = SnapshotType.AUTO;
    private int offset, capacity, left, panelWidth;
    private List<SnapshotInfo> snapshots = List.of();
    private boolean busy;
    private int refreshTicks;
    private RewindTheme.RepeatButton intervalDownButton, intervalUpButton;
    private static final int TOP = 100, CARD = 78;

    public RewindManagerScreen(Screen parent) {
        super(Component.translatable("screen.rewind.manager.title"));
        this.parent = parent;
    }
    private Component text(String key, Object... args) { return Component.translatable(key,args); }
    private Button add(String key, Button.OnPress action, int x, int y, int w) {
        return addRenderableWidget(RewindTheme.button(text(key),action,x,y,w));
    }
    private int count() { return category == SnapshotType.AUTO ? 3 : 5; }
    private SnapshotSlot slot(int index) {
        return category == SnapshotType.AUTO ? SnapshotSlot.automatic(index+1) : SnapshotSlot.manual(index+1);
    }
    private SnapshotInfo info(SnapshotSlot slot) {
        return snapshots.stream().filter(s -> s.slot().equals(slot)).findFirst().orElse(null);
    }
    @Override protected void init() {
        stopRepeating();
        intervalDownButton = intervalUpButton = null;
        panelWidth = Math.min(480, width-24);
        left = (width-panelWidth)/2;
        capacity = Math.max(1,(height-TOP-36)/CARD);
        offset = Math.min(offset,Math.max(0,count()-capacity));
        snapshots = SnapshotManager.get().snapshots();
        busy = SnapshotManager.get().isBusy();
        add("screen.rewind.manager.auto",b -> switchTo(SnapshotType.AUTO),left,34,panelWidth/2-2).active = category != SnapshotType.AUTO;
        add("screen.rewind.manager.manual",b -> switchTo(SnapshotType.MANUAL),left+panelWidth/2+2,34,panelWidth/2-2).active = category != SnapshotType.MANUAL;
        if (category == SnapshotType.AUTO) {
            add(SnapshotManager.get().settings().automatic ? "screen.rewind.auto_on" : "screen.rewind.auto_off",b -> {
                SnapshotManager.get().setAutomatic(!SnapshotManager.get().settings().automatic);
                  rebuildWidgets();
            },left,60,80).active=!busy;
            intervalDownButton = addRenderableWidget(RewindTheme.repeatButton(text("screen.rewind.manager.interval_down"),b -> interval(-1),left+panelWidth-48,60,22));
            intervalDownButton.active=!busy;
            intervalUpButton = addRenderableWidget(RewindTheme.repeatButton(text("screen.rewind.manager.interval_up"),b -> interval(1),left+panelWidth-22,60,22));
            intervalUpButton.active=!busy;
        }
        for (int row=0; row<capacity && offset+row<count(); row++) card(slot(offset+row),TOP+row*CARD);
        int footerWidth = (panelWidth - 18) / 4;
        add("screen.rewind.manager.previous",b -> move(-capacity),left,height-28,footerWidth).active = offset>0;
        add("screen.rewind.manager.cleanup",b -> confirmCleanup(),left+footerWidth+6,height-28,footerWidth).active=!busy;
        add("gui.done",b -> onClose(),left+2*(footerWidth+6),height-28,footerWidth);
        add("screen.rewind.manager.next",b -> move(capacity),left+3*(footerWidth+6),height-28,footerWidth).active = offset+capacity<count();
    }
    @Override public boolean isPauseScreen() { return false; }
    private void switchTo(SnapshotType type) { category=type; offset=0; rebuildWidgets(); }
    private void move(int amount) { offset=Math.max(0,Math.min(count()-Math.min(capacity,count()),offset+amount)); rebuildWidgets(); }
    private void interval(int delta) {
        if (SnapshotManager.get().isBusy()) { stopRepeating(); return; }
        int current = SnapshotManager.get().intervalMinutes();
        int next = Math.max(1,Math.min(1440,current+delta));
        if (next == current) return;
        SnapshotManager.get().setInterval(next);
    }
    private void confirm(String key, SnapshotSlot slot, Runnable action) {
        minecraft.setScreen(new ConfirmScreen(yes -> { minecraft.setScreen(this); if (yes) action.run(); },
                text(key),text("screen.rewind.confirm_slot",slot.number())));
    }
    private void confirmCleanup() {
        minecraft.setScreen(new ConfirmScreen(yes -> {
            minecraft.setScreen(this);
            if (yes) SnapshotManager.get().cleanupRestoreCache();
        }, text("screen.rewind.manager.cleanup_confirm"), text("screen.rewind.manager.cleanup_detail")));
    }
    private void card(SnapshotSlot slot,int y) {
        boolean present=info(slot)!=null;
        String expectedId = info(slot) == null ? "-" : info(slot).metadata().snapshotId;
        int n=slot.type()==SnapshotType.MANUAL ? 5 : 3;
        int bw=(panelWidth-20-(n-1)*4)/n, x=left+10;
        if (slot.type()==SnapshotType.MANUAL) {
            add("screen.rewind.manager.save",b -> {
                if (present) confirm("screen.rewind.overwrite",slot,() -> SnapshotManager.get().requestSnapshot(slot, expectedId));
                else SnapshotManager.get().requestSnapshot(slot, expectedId);
            },x,y+46,bw).active=!busy; x+=bw+4;
        }
        add("screen.rewind.manager.load",b -> SnapshotManager.get().requestRestore(slot),x,y+46,bw).active=present&&!busy; x+=bw+4;
        add("screen.rewind.manager.default_load",b -> { SnapshotManager.get().setDefaultRestoreSlot(slot);  },x,y+46,bw).active=present&&!busy; x+=bw+4;
        if (slot.type()==SnapshotType.MANUAL) {
            add("screen.rewind.manager.default_save",b -> { SnapshotManager.get().setDefaultManualSlot(slot);  },x,y+46,bw).active=!busy; x+=bw+4;
        }
        add("screen.rewind.manager.delete",b -> confirm("screen.rewind.delete_confirm",slot,() -> SnapshotManager.get().deleteSnapshot(slot, expectedId)),x,y+46,bw).active=present&&!busy;
    }
    @Override public boolean mouseScrolled(double x,double y,double delta) {
        if (y>=TOP && y<height-32 && delta!=0) { move(delta>0 ? -1 : 1); return true; }
        return super.mouseScrolled(x,y,delta);
    }
    @Override public void tick() {
        if (intervalDownButton != null) intervalDownButton.tickRepeat();
        if (intervalUpButton != null) intervalUpButton.tickRepeat();
        if (++refreshTicks>=20) {
            refreshTicks=0;
            // Rebuilding widgets would cancel a button currently being held.
            if ((intervalDownButton == null || !intervalDownButton.isRepeating()) && (intervalUpButton == null || !intervalUpButton.isRepeating())) rebuildWidgets();
        }
    }
    private void stopRepeating() {
        if (intervalDownButton != null) intervalDownButton.stopRepeating();
        if (intervalUpButton != null) intervalUpButton.stopRepeating();
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        if (button == 0) stopRepeating();
        return super.mouseReleased(x, y, button);
    }
    @Override public void removed() { stopRepeating(); super.removed(); }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public void render(GuiGraphics g,int mx,int my,float dt) {
        RewindTheme.background(g,width,height,false);
        g.drawCenteredString(font,title,width/2,17,RewindTheme.GOLD);
        if (!SnapshotManager.get().administrator()) {
            g.drawCenteredString(font,Component.translatable("screen.rewind.admin_only"),width/2,86,RewindTheme.GOLD);
            super.render(g,mx,my,dt);
            return;
        }
        if (category==SnapshotType.AUTO) {
            int interval = SnapshotManager.get().intervalMinutes();
            g.drawString(font,font.plainSubstrByWidth(text("screen.rewind.manager.interval",interval).getString(),panelWidth-140),left+86,66,RewindTheme.GOLD,false);
        } else {
            g.drawString(font,font.plainSubstrByWidth(text("screen.rewind.manager.defaults",SnapshotManager.get().settings().defaultManual,SnapshotManager.get().settings().defaultRestore).getString(),panelWidth),left,66,RewindTheme.MUTED,false);
        }
        g.drawString(font,text("screen.rewind.scroll_hint",offset+1,Math.min(count(),offset+capacity),count()),left,86,RewindTheme.MUTED,false);
        for(int row=0;row<capacity && offset+row<count();row++) {
            SnapshotSlot slot=slot(offset+row); SnapshotInfo info=info(slot); int y=TOP+row*CARD;
            g.fill(left,y,left+panelWidth,y+72,0xF01A201F);
            RewindTheme.frame(g,left,y,panelWidth,72,0xFF494333);
            g.fill(left,y,left+3,y+72,category==SnapshotType.AUTO ? 0xFF638B80 : 0xFFB48350);
            g.drawString(font,text(category==SnapshotType.AUTO ? "screen.rewind.manager.auto_slot" : "screen.rewind.manager.manual_slot",slot.number()),left+10,y+9,RewindTheme.GOLD,false);
            String badge = text(info==null ? "screen.rewind.manager.empty" : "screen.rewind.saved").getString();
            g.drawString(font,badge,left+panelWidth-10-font.width(badge),y+9,RewindTheme.MUTED,false);
            String details = info == null ? text("screen.rewind.empty_hint").getString()
                    : (info.metadata().displayName == null || info.metadata().displayName.isBlank()
                    ? info.metadata().worldName + " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(info.metadata().createdAtEpochMillis))
                    : info.metadata().displayName);
            g.drawString(font,font.plainSubstrByWidth(details,panelWidth-20),left+10,y+28,RewindTheme.MUTED,false);
            int buttonCount = category == SnapshotType.MANUAL ? 5 : 3;
            int buttonWidth = (panelWidth - 20 - (buttonCount - 1) * 4) / buttonCount;
            int firstButtonX = left + 10;
            int defaultLoadIndex = category == SnapshotType.MANUAL ? 2 : 1;
            if (slot.id().equals(SnapshotManager.get().settings().defaultRestore)) {
                drawDefaultMark(g, firstButtonX + defaultLoadIndex * (buttonWidth + 4), y + 42, buttonWidth);
            }
            if (category == SnapshotType.MANUAL && slot.number() == SnapshotManager.get().settings().defaultManual) {
                drawDefaultMark(g, firstButtonX + 3 * (buttonWidth + 4), y + 42, buttonWidth);
            }
        }
        super.render(g,mx,my,dt);
    }

    private void drawDefaultMark(GuiGraphics g, int buttonX, int y, int buttonWidth) {
        int x = buttonX + buttonWidth / 2;
        g.fill(x - 3, y + 2, x + 4, y + 3, RewindTheme.GOLD);
        g.fill(x - 1, y, x + 2, y + 5, RewindTheme.GOLD);
    }
}
