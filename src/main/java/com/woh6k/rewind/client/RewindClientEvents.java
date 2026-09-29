package com.woh6k.rewind.client;

import com.woh6k.rewind.Rewind;
import com.woh6k.rewind.snapshot.SnapshotManager;
import com.woh6k.rewind.snapshot.SnapshotSlot;
import com.woh6k.rewind.client.screen.RestoreConfirmScreen;
import com.woh6k.rewind.client.screen.RewindManagerScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Phase 1 input endpoint. Actions are deliberately deferred until their safe implementations exist. */
@Mod.EventBusSubscriber(modid = Rewind.MOD_ID, value = Dist.CLIENT)
public final class RewindClientEvents {
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        while (RewindKeyMappings.OPEN_MANAGER.consumeClick()) {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            minecraft.setScreen(new RewindManagerScreen(minecraft.screen));
        }
        while (RewindKeyMappings.QUICK_SAVE.consumeClick()) {
            SnapshotManager.get().requestQuickSnapshot();
        }
        while (RewindKeyMappings.QUICK_RESTORE.consumeClick()) {
            String value = SnapshotManager.get().settings().defaultRestore;
            try {
                String[] parts = value.split("_");
                SnapshotSlot slot = "auto".equals(parts[0]) ? SnapshotSlot.automatic(Integer.parseInt(parts[1])) : SnapshotSlot.manual(Integer.parseInt(parts[1]));
                SnapshotManager.get().requestRestore(slot);
            } catch (RuntimeException ignored) { }
        }
        SnapshotManager.get().tickAutomaticSnapshots();
    }

    private RewindClientEvents() {
    }
}
