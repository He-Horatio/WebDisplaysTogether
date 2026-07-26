/*
 * Copyright (C) 2018 BARBOTIN Nicolas
 */

package net.montoyo.wd.net.client_bound;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.montoyo.wd.WebDisplays;
import net.montoyo.wd.client.ClientProxy;
import net.montoyo.wd.miniserv.client.Client;
import net.montoyo.wd.net.BufferUtils;
import net.montoyo.wd.net.Packet;
import net.montoyo.wd.utilities.Log;

public class S2CMessageMiniservKey extends Packet {
	private byte[] encryptedKey;
	
	public S2CMessageMiniservKey(byte[] key) {
		encryptedKey = key;
	}
	
	public S2CMessageMiniservKey(FriendlyByteBuf buf) {
		super(buf);
		encryptedKey = BufferUtils.readBytes(buf);
	}
	
	@Override
	public void write(FriendlyByteBuf buf) {
		BufferUtils.writeBytes(buf, encryptedKey);
	}
	
	@Override
	public void handle(NetworkEvent.Context ctx) {
		if (checkClient(ctx)) {
			if (Client.getInstance().decryptKey(encryptedKey)) {
				Log.info("Successfully received and decrypted key, starting miniserv client...");
				if (WebDisplays.PROXY instanceof ClientProxy proxy) {
					proxy.startMiniservClient();
				}
			}
			
			ctx.setPacketHandled(true);
		}
	}
}
