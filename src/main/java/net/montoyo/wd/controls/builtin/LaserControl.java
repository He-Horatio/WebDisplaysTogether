package net.montoyo.wd.controls.builtin;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;
import net.montoyo.wd.controls.ScreenControl;
import net.montoyo.wd.core.MissingPermissionException;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.utilities.data.BlockSide;
import net.montoyo.wd.utilities.math.Vector2i;

import java.util.function.Function;

public class LaserControl extends ScreenControl {
	public static final ResourceLocation id = new ResourceLocation("webdisplaystogether:laser");
	
	public enum ControlType {
		MOVE, DOWN, UP
	}
	
	ControlType type;
	Vector2i coord;
	// Default must be -1: MOVE packets don't carry a button, and the server
	// distinguishes MOVE from UP by button == -1 (see ScreenBlockEntity.laserDownMove)
	int button = -1;
	
	public LaserControl(ControlType type, Vector2i coord) {
		this(type, coord, -1);
	}
	
	public LaserControl(ControlType type, Vector2i coord, int button) {
		super(id);
		this.type = type;
		this.coord = coord;
		this.button = button;
	}
	
	public LaserControl(FriendlyByteBuf buf) {
		super(id);
		type = ControlType.values()[buf.readByte()];
		if (!type.equals(ControlType.UP))
			coord = new Vector2i(buf);
		if (!type.equals(ControlType.MOVE))
			button = buf.readInt();
	}
	
	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeByte(type.ordinal());
		if (coord != null) coord.writeTo(buf);
		if (type != ControlType.MOVE) buf.writeInt(button);
	}
	
	@Override
	public void handleServer(BlockPos pos, BlockSide side, ScreenBlockEntity tes, NetworkEvent.Context ctx, Function<Integer, Boolean> permissionChecker) throws MissingPermissionException {
		// feel like this makes sense, but I wanna get opinions first
//		checkPerms(ScreenRights.INTERACT, permissionChecker, ctx.getSender());
		ServerPlayer sender = ctx.getSender();
		switch (type) {
			case UP -> tes.laserUp(side, sender, button);
			case DOWN -> tes.laserDownMove(side, sender, coord, true, button);
			case MOVE -> tes.laserDownMove(side, sender, coord, false, button);
		}
	}
	
	@Override
	@OnlyIn(Dist.CLIENT)
	public void handleClient(BlockPos pos, BlockSide side, ScreenBlockEntity tes, NetworkEvent.Context ctx) {
		// Input is injected into the server-side browser; nothing to do client-side.
	}
}
