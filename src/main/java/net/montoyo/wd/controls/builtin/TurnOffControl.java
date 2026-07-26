package net.montoyo.wd.controls.builtin;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;
import net.montoyo.wd.WebDisplays;
import net.montoyo.wd.controls.ScreenControl;
import net.montoyo.wd.core.MissingPermissionException;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.utilities.data.BlockSide;

import java.util.function.Function;

public class TurnOffControl extends ScreenControl {
	public static final ResourceLocation id = new ResourceLocation("webdisplaystogether:deactivate");

	public static final TurnOffControl INSTANCE = new TurnOffControl();

	public TurnOffControl() {
		super(id);
	}
	
	@Override
	public void write(FriendlyByteBuf buf) {
	}
	
	@Override
	public void handleServer(BlockPos pos, BlockSide side, ScreenBlockEntity tes, NetworkEvent.Context ctx, Function<Integer, Boolean> permissionChecker) throws MissingPermissionException {
		throw new RuntimeException("Cannot handle deactivation packet from server");
	}
	
	@Override
	@OnlyIn(Dist.CLIENT)
	public void handleClient(BlockPos pos, BlockSide side, ScreenBlockEntity tes, NetworkEvent.Context ctx) {
		if (side != null) {
			WebDisplays.PROXY.closeGui(pos, side);
			tes.disableScreen(side);
		} else {
			for (BlockSide value : BlockSide.values()) {
				WebDisplays.PROXY.closeGui(pos, value);
				tes.disableScreen(value);
			}
		}
	}
}
