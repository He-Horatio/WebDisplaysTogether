package net.montoyo.wd.net.client_bound;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.montoyo.wd.client.audio.ScreenAudioManager;
import net.montoyo.wd.net.Packet;
import net.montoyo.wd.utilities.data.BlockSide;

/**
 * Server -> client: one Opus-encoded audio packet for a screen.
 */
public class S2CMessageStreamAudio extends Packet {
    public BlockPos pos;
    public BlockSide side;
    public byte[] data;

    public S2CMessageStreamAudio(BlockPos pos, BlockSide side, byte[] data) {
        this.pos = pos;
        this.side = side;
        this.data = data;
    }

    public S2CMessageStreamAudio(FriendlyByteBuf buf) {
        super(buf);
        pos = buf.readBlockPos();
        side = BlockSide.values()[buf.readByte()];
        data = buf.readByteArray();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeByte(side.ordinal());
        buf.writeByteArray(data);
    }

    @Override
    public boolean isSkippable() {
        return true;
    }

    @Override
    public void handle(NetworkEvent.Context ctx) {
        if (!checkClient(ctx))
            return;

        ctx.enqueueWork(() -> ScreenAudioManager.handleAudio(this));
    }
}
