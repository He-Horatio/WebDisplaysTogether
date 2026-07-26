package net.montoyo.wd.net.server_bound;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;
import net.montoyo.wd.core.ScreenRights;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.entity.SpeakerBlockEntity;
import net.montoyo.wd.net.Packet;

/**
 * Client -> server: sets a speaker's position in the sound modeling map.
 */
public class C2SMessageSpeakerPos extends Packet {
    private BlockPos pos;
    private float relX, relY;

    public C2SMessageSpeakerPos(BlockPos pos, float relX, float relY) {
        this.pos = pos;
        this.relX = relX;
        this.relY = relY;
    }

    public C2SMessageSpeakerPos(FriendlyByteBuf buf) {
        super(buf);
        pos = buf.readBlockPos();
        relX = buf.readFloat();
        relY = buf.readFloat();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeFloat(relX);
        buf.writeFloat(relY);
    }

    @Override
    public void handle(NetworkEvent.Context ctx) {
        if (!checkServer(ctx))
            return;

        ctx.enqueueWork(() -> {
            ServerPlayer ply = ctx.getSender();
            if (ply == null || ply.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64.0 * 64.0)
                return;

            BlockEntity be = ply.level().getBlockEntity(pos);
            if (!(be instanceof SpeakerBlockEntity speaker))
                return;

            // Configuring a speaker requires upgrade-management rights on the linked screen
            ScreenBlockEntity tes = speaker.getConnectedScreenEx();
            if (tes != null) {
                ScreenData scr = tes.getScreen(speaker.getScreenSide());
                if (scr != null && (scr.rightsFor(ply) & ScreenRights.BIND_DEVICES) == 0)
                    return;
            }

            speaker.setRelPos(relX, relY);
        });
        ctx.setPacketHandled(true);
    }
}
