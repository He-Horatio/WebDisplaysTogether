package net.montoyo.wd.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketListener;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import net.montoyo.wd.utilities.DistSafety;

import java.util.ArrayList;
import java.util.function.Supplier;

public class Packet implements net.minecraft.network.protocol.Packet {
	public Packet() {
	}
	
	public Packet(FriendlyByteBuf buf) {
	
	}
	
	public void write(FriendlyByteBuf buf) {
	}
	
	public void handle(NetworkEvent.Context ctx) {
	}
	
	public final void handle(PacketListener pHandler) {
	}
	
	public boolean isSkippable() {
		return net.minecraft.network.protocol.Packet.super.isSkippable();
	}
	
	public boolean checkClient(NetworkEvent.Context ctx) {
		return ctx.getDirection().getReceptionSide().isClient();
	}
	
	public boolean checkServer(NetworkEvent.Context ctx) {
		return ctx.getDirection().getReceptionSide().isServer();
	}
	
	public void respond(NetworkEvent.Context ctx, Packet packet) {
		ctx.enqueueWork(() -> WDNetworkRegistry.INSTANCE.reply(packet, ctx));
	}
	
	private static final ArrayList<Runnable> runLater = new ArrayList<>();
	
	public void respondLater(NetworkEvent.Context ctx, Packet packet) {
		ctx.enqueueWork(() -> runLater.add(() -> {
			if (checkClient(ctx))
				WDNetworkRegistry.INSTANCE.sendToServer(packet);
			else if (ctx.getSender() != null)
				WDNetworkRegistry.INSTANCE.send(PacketDistributor.PLAYER.with(ctx::getSender), packet);
			else WDNetworkRegistry.INSTANCE.reply(packet, ctx);
		}));
	}
	
	public static void onTick(TickEvent.RenderTickEvent event) {
		if (event.phase.equals(TickEvent.Phase.END)) {
			if (!runLater.isEmpty()) {
				if (DistSafety.isConnected()) {
					for (Runnable runnable : runLater) runnable.run();
					runLater.clear();
				}
			}
		}
	}
	
	static {
		MinecraftForge.EVENT_BUS.addListener(Packet::onTick);
	}
	
	public final void handle(Supplier<NetworkEvent.Context> contextSupplier) {
	}
}
