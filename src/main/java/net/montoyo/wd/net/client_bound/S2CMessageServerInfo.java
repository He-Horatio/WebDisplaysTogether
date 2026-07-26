/*
 * Copyright (C) 2018 BARBOTIN Nicolas
 */

package net.montoyo.wd.net.client_bound;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.montoyo.wd.WebDisplays;
import net.montoyo.wd.miniserv.client.Client;
import net.montoyo.wd.net.Packet;
import net.montoyo.wd.net.server_bound.C2SMessageMiniservConnect;

public class S2CMessageServerInfo extends Packet {
	
	private int miniservPort;
	
	public S2CMessageServerInfo(int msPort) {
		miniservPort = msPort;
	}
	
	public S2CMessageServerInfo(FriendlyByteBuf buf) {
		super(buf);
		miniservPort = buf.readShort();
	}
	
	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeShort(miniservPort);
	}
	
	@Override
	public void handle(NetworkEvent.Context ctx) {
		if (checkClient(ctx)) {
			try {
				WebDisplays.PROXY.setMiniservClientPort(miniservPort);
				C2SMessageMiniservConnect message = Client.getInstance().beginConnection();
				respond(ctx, message);
				ctx.setPacketHandled(true);
			} catch (Throwable err) {
				err.printStackTrace();
				throw new RuntimeException(err);
			}
		}
	}
}
