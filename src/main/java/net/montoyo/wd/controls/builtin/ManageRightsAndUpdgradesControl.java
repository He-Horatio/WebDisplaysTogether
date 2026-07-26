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
import net.montoyo.wd.core.ScreenRights;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.utilities.data.BlockSide;

import java.util.function.Function;

/**
 * Synchronizes friend/other permission masks. (The upgrade management part was
 * removed in 3.0 since screens now have all capabilities built in.)
 */
@Deprecated
public class ManageRightsAndUpdgradesControl extends ScreenControl {
	public static final ResourceLocation id = new ResourceLocation("webdisplaystogether:mod_rights_upgrades");
	
	private final int friendRights;
	private final int otherRights;
	
	public ManageRightsAndUpdgradesControl(int friendRights, int otherRights) {
		super(id);
		this.friendRights = friendRights;
		this.otherRights = otherRights;
	}
	
	public ManageRightsAndUpdgradesControl(FriendlyByteBuf buf) {
		super(id);
		friendRights = buf.readInt();
		otherRights = buf.readInt();
	}
	
	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeInt(friendRights);
		buf.writeInt(otherRights);
	}
	
	@Override
	public void handleServer(BlockPos pos, BlockSide side, ScreenBlockEntity tes, NetworkEvent.Context ctx, Function<Integer, Boolean> permissionChecker) throws MissingPermissionException {
		ServerPlayer player = ctx.getSender();
		ScreenData scr = tes.getScreen(side);
		
		int fr = scr.owner.uuid.equals(player.getGameProfile().getId()) ? friendRights : scr.friendRights;
		int or = (scr.rightsFor(player) & ScreenRights.MANAGE_OTHER_RIGHTS) == 0 ? scr.otherRights : otherRights;
		
		if(scr.friendRights != fr || scr.otherRights != or)
			tes.setRights(player, side, fr, or);
	}
	
	@Override
	@OnlyIn(Dist.CLIENT)
	public void handleClient(BlockPos pos, BlockSide side, ScreenBlockEntity tes, NetworkEvent.Context ctx) {
		ScreenData scr = tes.getScreen(side);
		
		if(scr.friendRights != friendRights || scr.otherRights != otherRights)
			tes.setRights(null, side, friendRights, otherRights);
	}
}
