package com.woh6k.rewind.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.woh6k.rewind.Rewind;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Key mappings are client-only and remain editable in Minecraft controls. */
@Mod.EventBusSubscriber(modid = Rewind.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class RewindKeyMappings {
    public static final String CATEGORY = "key.categories.rewind";
    public static final KeyMapping OPEN_MANAGER = new KeyMapping(
            "key.rewind.open_manager", InputConstants.KEY_K, CATEGORY);
    public static final KeyMapping QUICK_SAVE = new KeyMapping(
            "key.rewind.quick_save", InputConstants.KEY_F6, CATEGORY);
    public static final KeyMapping QUICK_RESTORE = new KeyMapping(
            "key.rewind.quick_restore", InputConstants.KEY_F7, CATEGORY);

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(OPEN_MANAGER);
        event.register(QUICK_SAVE);
        event.register(QUICK_RESTORE);
    }

    private RewindKeyMappings() {
    }
}
