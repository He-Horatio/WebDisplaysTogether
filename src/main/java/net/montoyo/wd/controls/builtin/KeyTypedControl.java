package net.montoyo.wd.controls.builtin;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;
import net.montoyo.wd.controls.ScreenControl;
import net.montoyo.wd.core.MissingPermissionException;
import net.montoyo.wd.core.ScreenRights;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.utilities.data.BlockSide;

import java.util.function.Function;

public class KeyTypedControl extends ScreenControl {
	public static final ResourceLocation id = new ResourceLocation("webdisplaystogether:type");
	
	String text;
	BlockPos soundPos;
	
	public KeyTypedControl(String text, BlockPos soundPos) {
		super(id);
		this.text = text;
		this.soundPos = soundPos;
	}
	
	public KeyTypedControl(FriendlyByteBuf buf) {
		super(id);
		text = buf.readUtf();
		soundPos = buf.readBlockPos();
	}
	
	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeUtf(text);
		buf.writeBlockPos(soundPos);
	}
	
	@Override
	public void handleServer(BlockPos pos, BlockSide side, ScreenBlockEntity tes, NetworkEvent.Context ctx, Function<Integer, Boolean> permissionChecker) throws MissingPermissionException {
		checkPerms(ScreenRights.INTERACT, permissionChecker, ctx.getSender());
		tes.type(side, text, soundPos, ctx.getSender());
	}
	
	@Override
	@OnlyIn(Dist.CLIENT)
	public void handleClient(BlockPos pos, BlockSide side, ScreenBlockEntity tes, NetworkEvent.Context ctx) {
		tes.type(side, text, soundPos);
	}
}
