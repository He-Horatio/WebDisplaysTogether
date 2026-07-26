package net.montoyo.wd.controls.builtin;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;
import net.montoyo.wd.controls.ScreenControl;
import net.montoyo.wd.core.BrowseMode;
import net.montoyo.wd.core.MissingPermissionException;
import net.montoyo.wd.core.ScreenRights;
import net.montoyo.wd.core.ScreenSoundMode;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.utilities.data.BlockSide;

import java.util.function.Function;

/**
 * Carries the "extra" screen settings introduced by the server-rendering fork:
 * browse mode (server/local), brightness, volume and sound output mode.
 */
public class ScreenSettingsControl extends ScreenControl {
	public static final ResourceLocation id = new ResourceLocation("webdisplaystogether:screen_settings");
	
	private final BrowseMode browseMode;
	private final int brightness;
	private final int volume;
	private final ScreenSoundMode soundMode;
	
	public ScreenSettingsControl(BrowseMode browseMode, int brightness, int volume, ScreenSoundMode soundMode) {
		super(id);
		this.browseMode = browseMode;
		this.brightness = brightness;
		this.volume = volume;
		this.soundMode = soundMode;
	}
	
	public ScreenSettingsControl(FriendlyByteBuf buf) {
		super(id);
		browseMode = BrowseMode.of(buf.readByte());
		brightness = buf.readShort();
		volume = buf.readShort();
		soundMode = ScreenSoundMode.of(buf.readByte());
	}
	
	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeByte(browseMode.ordinal());
		buf.writeShort(brightness);
		buf.writeShort(volume);
		buf.writeByte(soundMode.ordinal());
	}
	
	@Override
	public void handleServer(BlockPos pos, BlockSide side, ScreenBlockEntity tes, NetworkEvent.Context ctx, Function<Integer, Boolean> permissionChecker) throws MissingPermissionException {
		checkPerms(ScreenRights.BIND_DEVICES, permissionChecker, ctx.getSender());
		tes.setScreenSettings(side, browseMode, clamp(brightness), clamp(volume), soundMode);
	}
	
	@Override
	@OnlyIn(Dist.CLIENT)
	public void handleClient(BlockPos pos, BlockSide side, ScreenBlockEntity tes, NetworkEvent.Context ctx) {
		tes.setScreenSettings(side, browseMode, clamp(brightness), clamp(volume), soundMode);
	}
	
	private static int clamp(int v) {
		return Math.max(0, Math.min(300, v));
	}
}
