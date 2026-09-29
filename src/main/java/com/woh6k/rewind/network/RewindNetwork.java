package com.woh6k.rewind.network;

import com.google.gson.Gson;
import com.woh6k.rewind.server.ServerSnapshots;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import java.util.Optional;

public final class RewindNetwork {
    public static final Gson JSON=new Gson();
    public static final SimpleChannel CHANNEL=NetworkRegistry.newSimpleChannel(new ResourceLocation("rewind","control"),()->"3","3"::equals,"3"::equals);
    public record Request(String action,String value) {}
    public record Reply(String kind,String value) {}
    public static void register() {
        CHANNEL.registerMessage(0,Request.class,(p,b)->{b.writeUtf(p.action(),32);b.writeUtf(p.value(),128);},b->new Request(b.readUtf(32),b.readUtf(128)),(p,c)->{
            var context=c.get(); context.enqueueWork(()->ServerSnapshots.handle(context.getSender(),p.action(),p.value())); context.setPacketHandled(true);
        },Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1,Reply.class,(p,b)->{b.writeUtf(p.kind(),32);b.writeUtf(p.value(),32767);},b->new Reply(b.readUtf(32),b.readUtf(32767)),(p,c)->{
            c.get().enqueueWork(()->DistExecutor.unsafeRunWhenOn(Dist.CLIENT,()->()->com.woh6k.rewind.client.ClientNetworkHandler.receive(p.kind(),p.value()))); c.get().setPacketHandled(true);
        },Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
    public static void send(ServerPlayer player,String kind,String value) { CHANNEL.send(PacketDistributor.PLAYER.with(()->player),new Reply(kind,value)); }
}
