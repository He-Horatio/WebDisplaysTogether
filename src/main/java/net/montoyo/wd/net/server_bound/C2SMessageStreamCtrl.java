package net.montoyo.wd.net.server_bound;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.montoyo.wd.net.Packet;
import net.montoyo.wd.serverbrowser.ServerBrowserManager;
import net.montoyo.wd.utilities.data.BlockSide;

/**
 * Client -> server stream control: subscribe to / unsubscribe from a screen's
 * video stream, or request a keyframe (e.g. after packet loss or when joining).
 */
public class C2SMessageStreamCtrl extends Packet {
    public static final int ACT_SUBSCRIBE = 0;
    public static final int ACT_UNSUBSCRIBE = 1;
    public static final int ACT_KEYFRAME = 2;
    /** Periodic delivery-quality report; see {@link #feedback}. */
    public static final int ACT_FEEDBACK = 3;

    private BlockPos pos;
    private BlockSide side;
    private int action;
    // ACT_FEEDBACK payload (one ~2s measurement window)
    private int framesReceived;
    private int videoStalls;
    private int audioGlitches;
    /** Smoothed interval between complete frame arrivals, ms (0 = unknown). */
    private int arrivalIntervalMs;
    /** Lowest preload buffer level seen while paced (255 = no paced playback). */
    private int minBufferedFrames = 255;

    public C2SMessageStreamCtrl(BlockPos pos, BlockSide side, int action) {
        this.pos = pos;
        this.side = side;
        this.action = action;
    }

    /**
     * Delivery report for one screen: complete video frames that arrived,
     * large gaps in their arrival (each one a visible stutter), audible audio
     * glitches (underruns/skips), plus the transmission speed of the preload
     * buffer (frame arrival interval and lowest buffered-frame level). The
     * server compares the arrival interval against its own send interval and
     * scales the stream resolution to what this viewer's connection actually
     * sustains.
     */
    public static C2SMessageStreamCtrl feedback(BlockPos pos, BlockSide side,
                                                int framesReceived, int videoStalls, int audioGlitches,
                                                int arrivalIntervalMs, int minBufferedFrames) {
        C2SMessageStreamCtrl msg = new C2SMessageStreamCtrl(pos, side, ACT_FEEDBACK);
        msg.framesReceived = Math.min(framesReceived, 0xFFFF);
        msg.videoStalls = Math.min(videoStalls, 0xFF);
        msg.audioGlitches = Math.min(audioGlitches, 0xFF);
        msg.arrivalIntervalMs = Math.max(0, Math.min(arrivalIntervalMs, 0xFF));
        msg.minBufferedFrames = minBufferedFrames < 0 ? 0xFF : Math.min(minBufferedFrames, 0xFE);
        return msg;
    }

    public C2SMessageStreamCtrl(FriendlyByteBuf buf) {
        super(buf);
        pos = buf.readBlockPos();
        side = BlockSide.values()[buf.readByte()];
        action = buf.readByte();
        if (action == ACT_FEEDBACK) {
            framesReceived = buf.readUnsignedShort();
            videoStalls = buf.readUnsignedByte();
            audioGlitches = buf.readUnsignedByte();
            arrivalIntervalMs = buf.readUnsignedByte();
            minBufferedFrames = buf.readUnsignedByte();
        }
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeByte(side.ordinal());
        buf.writeByte(action);
        if (action == ACT_FEEDBACK) {
            buf.writeShort(framesReceived);
            buf.writeByte(videoStalls);
            buf.writeByte(audioGlitches);
            buf.writeByte(arrivalIntervalMs);
            buf.writeByte(minBufferedFrames);
        }
    }

    @Override
    public void handle(NetworkEvent.Context ctx) {
        if (!checkServer(ctx))
            return;

        ServerPlayer ply = ctx.getSender();
        if (ply == null)
            return;

        ctx.enqueueWork(() -> {
            switch (action) {
                case ACT_SUBSCRIBE -> ServerBrowserManager.subscribe(ply, pos, side);
                case ACT_UNSUBSCRIBE -> ServerBrowserManager.unsubscribe(ply, pos, side);
                case ACT_KEYFRAME -> ServerBrowserManager.requestKeyframe(ply, pos, side);
                case ACT_FEEDBACK -> ServerBrowserManager.handleFeedback(ply, pos, side, framesReceived, videoStalls,
                        audioGlitches, arrivalIntervalMs, minBufferedFrames == 0xFF ? -1 : minBufferedFrames);
            }
        });
    }
}
