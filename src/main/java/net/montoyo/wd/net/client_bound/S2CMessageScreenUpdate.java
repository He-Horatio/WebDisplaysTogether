/*
 * Copyright (C) 2018 BARBOTIN Nicolas
 */

package net.montoyo.wd.net.client_bound;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;
import net.montoyo.wd.WebDisplays;
import net.montoyo.wd.controls.ScreenControl;
import net.montoyo.wd.controls.ScreenControlRegistry;
import net.montoyo.wd.controls.builtin.*;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.net.BufferUtils;
import net.montoyo.wd.net.Packet;
import net.montoyo.wd.utilities.math.Vector2i;
import net.montoyo.wd.utilities.math.Vector3i;
import net.montoyo.wd.utilities.data.BlockSide;
import net.montoyo.wd.utilities.data.Rotation;
import net.montoyo.wd.utilities.serialization.NameUUIDPair;

// TODO: use registry based approach
public class S2CMessageScreenUpdate extends Packet  {
    ScreenControl control;
    BlockPos pos;
    BlockSide side;
    
    public S2CMessageScreenUpdate(BlockPos blockPos, BlockSide side) {
        this.pos = blockPos;
        this.side = side;
    }
    
    public S2CMessageScreenUpdate(FriendlyByteBuf buf) {
        super(buf);
    
        pos = buf.readBlockPos();
        side = (BlockSide) BufferUtils.readEnum(buf, (i) -> BlockSide.values()[i], (byte) 1);
    
        this.control = ScreenControlRegistry.parse(buf);
    }
    
    public static S2CMessageScreenUpdate setURL(ScreenBlockEntity screen, BlockSide side, String weburl) {
        S2CMessageScreenUpdate screenUpdate = new S2CMessageScreenUpdate(screen.getBlockPos(), side);
        screenUpdate.control = new SetURLControl(weburl, new Vector3i(screenUpdate.pos));
        return screenUpdate;
    }
    
    public static S2CMessageScreenUpdate setResolution(ScreenBlockEntity screen, BlockSide side, Vector2i res) {
        S2CMessageScreenUpdate screenUpdate = new S2CMessageScreenUpdate(screen.getBlockPos(), side);
        screenUpdate.control = new ScreenModifyControl(res);
        return screenUpdate;
    }
    
    public static S2CMessageScreenUpdate rotation(ScreenBlockEntity screen, BlockSide side, Rotation rot) {
        S2CMessageScreenUpdate screenUpdate = new S2CMessageScreenUpdate(screen.getBlockPos(), side);
        screenUpdate.control = new ScreenModifyControl(rot);
        return screenUpdate;
    }
    
    public static S2CMessageScreenUpdate click(ScreenBlockEntity screen, BlockSide side, ClickControl.ControlType mouseMove, Vector2i pos) {
        S2CMessageScreenUpdate screenUpdate = new S2CMessageScreenUpdate(screen.getBlockPos(), side);
        screenUpdate.control = new ClickControl(mouseMove, pos);
        return screenUpdate;
    }

    public static S2CMessageScreenUpdate click(ScreenBlockEntity screen, BlockSide side, ClickControl.ControlType mouseMove, Vector2i pos, int button) {
        S2CMessageScreenUpdate screenUpdate = new S2CMessageScreenUpdate(screen.getBlockPos(), side);
        screenUpdate.control = new ClickControl(mouseMove, pos, button);
        return screenUpdate;
    }
    
    public static S2CMessageScreenUpdate type(ScreenBlockEntity screen, BlockSide side, String text) {
        S2CMessageScreenUpdate screenUpdate = new S2CMessageScreenUpdate(screen.getBlockPos(), side);
        screenUpdate.control = new KeyTypedControl(text, screenUpdate.pos);
        return screenUpdate;
    }
    
    public static S2CMessageScreenUpdate autoVolume(ScreenBlockEntity screen, BlockSide side, boolean av) {
        S2CMessageScreenUpdate screenUpdate = new S2CMessageScreenUpdate(screen.getBlockPos(), side);
        screenUpdate.control = new AutoVolumeControl(av);
        return screenUpdate;
    }

    public static S2CMessageScreenUpdate settings(ScreenBlockEntity screen, BlockSide side, net.montoyo.wd.core.BrowseMode bm, int brightness, int volume, net.montoyo.wd.core.ScreenSoundMode sm) {
        S2CMessageScreenUpdate screenUpdate = new S2CMessageScreenUpdate(screen.getBlockPos(), side);
        screenUpdate.control = new ScreenSettingsControl(bm, brightness, volume, sm);
        return screenUpdate;
    }
    
    public static S2CMessageScreenUpdate owner(ScreenBlockEntity screen, BlockSide side, NameUUIDPair owner) {
        S2CMessageScreenUpdate screenUpdate = new S2CMessageScreenUpdate(screen.getBlockPos(), side);
        screenUpdate.control = new OwnerControl(owner);
        return screenUpdate;
    }

    public static S2CMessageScreenUpdate turnOff(BlockPos blockPos, BlockSide side) {
        S2CMessageScreenUpdate screenUpdate = new S2CMessageScreenUpdate(blockPos, side);
        screenUpdate.control = TurnOffControl.INSTANCE;
        return screenUpdate;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        BufferUtils.writeEnum(buf, side, (byte) 1);
    
        buf.writeUtf(control.getId().toString());
        control.write(buf);
    }
    
    public void handle(NetworkEvent.Context ctx) {
        if (checkClient(ctx)) {
            ctx.enqueueWork(() -> {
                Level level = (Level) WebDisplays.PROXY.getWorld(ctx);
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof ScreenBlockEntity tes) {
                    control.handleClient(pos, side, tes, ctx);
                }
            });
            ctx.setPacketHandled(true);
            
//                switch (action) {
//                    case UPDATE_URL -> {
//                        try {
//                            tes.setScreenURL(side, string);
//                        } catch (IOException e) {
//                            throw new RuntimeException(e);
//                        }
//                    }
//                    case UPDATE_MOUSE -> tes.handleMouseEvent(side, mouseEvent, vec2i, button);
//                    case UPDATE_DELETE -> tes.removeScreen(side);
//                    case UPDATE_RESOLUTION -> tes.setResolution(side, vec2i);
//                    case UPDATE_TYPE -> tes.type(side, string, null);
//                    case UPDATE_RUN_JS -> tes.evalJS(side, string);
//                    case UPDATE_UPGRADES -> tes.updateUpgrades(side, upgrades);
//                    case UPDATE_JS_REDSTONE -> tes.updateJSRedstone(side, vec2i, redstoneLevel);
//                    case UPDATE_OWNER -> {
//                        TileEntityScreen.Screen scr = tes.getScreen(side);
//                        if (scr != null)
//                            scr.owner = owner;
//                    }
//                    case UPDATE_ROTATION -> tes.setRotation(side, rotation);
//                    case UPDATE_AUTO_VOL -> tes.setAutoVolume(side, autoVolume);
//                    default -> Log.warning("Caught invalid CMessageScreenUpdate with action ID %d", action);
//                }
        }
    }
}
