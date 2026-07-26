package net.montoyo.wd.controls.builtin;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;
import net.montoyo.wd.controls.ScreenControl;
import net.montoyo.wd.core.MissingPermissionException;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.utilities.data.BlockSide;
import net.montoyo.wd.utilities.math.Vector2i;

import java.util.function.Function;

/**
 * S2C mouse event broadcast, used by LOCAL browse mode: the server relays
 * clicks to every viewer, which injects them into its own local browser.
 */
public class ClickControl extends ScreenControl {
	public static final ResourceLocation id = new ResourceLocation("webdisplaystogether:click");
	
	public enum ControlType {
		CLICK, MOVE, DOWN, UP
	}
	
	ControlType type;
	Vector2i coord;
	int button = -1;
	
	public ClickControl(ControlType type, Vector2i coord) {
		this(type, coord, 1); //Historically clicks were always "left" (CEF button 1 maps to left, see handleMouseEvent)
	}
	
	public ClickControl(ControlType type, Vector2i coord, int button) {
		super(id);
		this.type = type;
		this.coord = coord;
		this.button = button;
	}
	
	public ClickControl(FriendlyByteBuf buf) {
		super(id);
		type = ControlType.values()[buf.readByte()];
		if (buf.readBoolean())
			coord = new Vector2i(buf);
		button = buf.readInt();
	}
	
	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeByte(type.ordinal());
		buf.writeBoolean(coord != null);
		if (coord != null)
			coord.writeTo(buf);
		buf.writeInt(button);
	}
	
	@Override
	public void handleServer(BlockPos pos, BlockSide side, ScreenBlockEntity tes, NetworkEvent.Context ctx, Function<Integer, Boolean> permissionChecker) throws MissingPermissionException {
		throw new RuntimeException("Cannot call click control on server");
	}
	
	@Override
	@OnlyIn(Dist.CLIENT)
	public void handleClient(BlockPos pos, BlockSide side, ScreenBlockEntity tes, NetworkEvent.Context ctx) {
		tes.handleMouseEvent(side, type, coord, button);
	}
}
