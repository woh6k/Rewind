package com.woh6k.rewind;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(Rewind.MOD_ID)
public class Rewind {
    public static final String MOD_ID = "rewind";

    public Rewind(FMLJavaModLoadingContext context) {
        com.woh6k.rewind.network.RewindNetwork.register();
        context.getModEventBus().addListener((net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent event) ->
                event.enqueueWork(com.woh6k.rewind.server.OfflineRestore::applyAll));
    }
}
