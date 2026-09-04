package net.montoyo.wd.net.client_bound;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.montoyo.wd.client.stream.ClientStreamManager;
import net.montoyo.wd.net.Packet;
import net.montoyo.wd.utilities.data.BlockSide;

/**
 * Server -> client: one chunk of an encoded video frame for a screen.
 * Frames larger than {@link #MAX_CHUNK_SIZE} are split into multiple chunks.
 */
public class S2CMessageStreamFrame extends Packet {
    public static final int MAX_CHUNK_SIZE = 30000;

    public BlockPos pos;
    public BlockSide side;
    public int streamId;   // increments when the server-side browser is (re)created
    public int seq;        // frame sequence number within the stream
    public boolean keyframe;
    public byte codec;     // StreamCodec wire id (0 = VP8, 1 = VP9)
    public byte chunkIdx;
    public byte chunkCount;
    /**
     * The server's smoothed send interval in ms (0 = unknown/sporadic,
     * clamped to 250). Lets the client tell "the page paints rarely" apart
     * from "a 30fps stream is arriving late because the link is congested",
     * and pace its playback on the sender's clock instead of the (jittery)
     * arrival clock.
     */
    public int paceMs;
    public byte[] data;

    public S2CMessageStreamFrame(BlockPos pos, BlockSide side, int streamId, int seq,
                                 boolean keyframe, byte codec, byte chunkIdx, byte chunkCount, int paceMs, byte[] data) {
        this.pos = pos;
        this.side = side;
        this.streamId = streamId;
        this.seq = seq;
        this.keyframe = keyframe;
        this.codec = codec;
        this.chunkIdx = chunkIdx;
        this.chunkCount = chunkCount;
        this.paceMs = paceMs;
        this.data = data;
    }

    public S2CMessageStreamFrame(FriendlyByteBuf buf) {
        super(buf);
        pos = buf.readBlockPos();
        side = BlockSide.values()[buf.readByte()];
        streamId = buf.readVarInt();
        seq = buf.readVarInt();
        keyframe = buf.readBoolean();
        codec = buf.readByte();
        chunkIdx = buf.readByte();
        chunkCount = buf.readByte();
        paceMs = buf.readUnsignedByte();
        data = buf.readByteArray();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeByte(side.ordinal());
        buf.writeVarInt(streamId);
        buf.writeVarInt(seq);
        buf.writeBoolean(keyframe);
        buf.writeByte(codec);
        buf.writeByte(chunkIdx);
        buf.writeByte(chunkCount);
        buf.writeByte(Math.max(0, Math.min(250, paceMs)));
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

        ctx.enqueueWork(() -> ClientStreamManager.handleFrame(this));
    }
}
