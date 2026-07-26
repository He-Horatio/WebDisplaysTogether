/*
 * Copyright (C) 2018 BARBOTIN Nicolas
 */

package net.montoyo.wd.net.client_bound;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.montoyo.wd.WebDisplays;
import net.montoyo.wd.net.Packet;
import net.montoyo.wd.utilities.data.BlockSide;

import java.util.Arrays;

public class S2CMessageCloseGui extends Packet {
	private BlockPos blockPos;
	private BlockSide blockSide;
	
	public S2CMessageCloseGui(BlockPos bp) {
		blockPos = bp;
		blockSide = null;
	}
	
	public S2CMessageCloseGui(BlockPos bp, BlockSide side) {
		blockPos = bp;
		blockSide = side;
	}
	
	public S2CMessageCloseGui(FriendlyByteBuf buf) {
		super(buf);
		blockPos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
		byte b = buf.readByte();
		if (b <= 0) blockSide = null;
		else blockSide = BlockSide.values()[b - 1];
	}
	
	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeInt(blockPos.getX());
		buf.writeInt(blockPos.getY());
		buf.writeInt(blockPos.getZ());
		
		if (blockSide == null) buf.writeByte(0);
		else buf.writeByte(blockSide.ordinal() + 1);
	}
	
	public void handle(NetworkEvent.Context ctx) {
		if (checkClient(ctx)) {
			ctx.enqueueWork(() -> {
				if (blockSide == null)
					Arrays.stream(BlockSide.values()).forEach(s -> WebDisplays.PROXY.closeGui(blockPos, s));
				else
					WebDisplays.PROXY.closeGui(blockPos, blockSide);
			});
			ctx.setPacketHandled(true);
		}
	}
}
