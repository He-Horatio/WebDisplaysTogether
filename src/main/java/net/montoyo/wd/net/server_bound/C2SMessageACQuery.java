/*
 * Copyright (C) 2018 BARBOTIN Nicolas
 */

package net.montoyo.wd.net.server_bound;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import net.montoyo.wd.WebDisplays;
import net.montoyo.wd.net.Packet;
import net.montoyo.wd.net.WDNetworkRegistry;
import net.montoyo.wd.net.client_bound.S2CMessageACResult;
import net.montoyo.wd.utilities.serialization.NameUUIDPair;

import java.util.Arrays;

public class C2SMessageACQuery extends Packet implements Runnable {
	private ServerPlayer player;
	private String beginning;
	private boolean matchExact;
	
	public C2SMessageACQuery(String beg, boolean exact) {
		beginning = beg;
		matchExact = exact;
	}
	
	public C2SMessageACQuery(FriendlyByteBuf buf) {
		super(buf);
		beginning = buf.readUtf();
		matchExact = buf.readBoolean();
	}
	
	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeUtf(beginning);
		buf.writeBoolean(matchExact);
	}
	
	@Override
	public void run() {
		GameProfile[] profiles = WebDisplays.PROXY.getOnlineGameProfiles();
		NameUUIDPair[] result;
		
		if (matchExact)
			result = Arrays.stream(profiles).filter(gp -> gp.getName().equalsIgnoreCase(beginning)).map(NameUUIDPair::new).toArray(NameUUIDPair[]::new);
		else {
			final String lBeg = beginning.toLowerCase();
			result = Arrays.stream(profiles).filter(gp -> gp.getName().toLowerCase().startsWith(lBeg)).map(NameUUIDPair::new).toArray(NameUUIDPair[]::new);
		}
		
		WDNetworkRegistry.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), new S2CMessageACResult(result));
	}
	
	public void handle(NetworkEvent.Context ctx) {
		if (checkServer(ctx)) {
			player = ctx.getSender();
			ctx.enqueueWork(this);
			ctx.setPacketHandled(true);
		}
	}
}
