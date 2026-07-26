package net.montoyo.wd.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.state.BlockState;
import net.montoyo.wd.client.audio.ClientSpeakerRegistry;
import net.montoyo.wd.registry.TileRegistry;
import net.montoyo.wd.utilities.data.BlockSide;
import net.montoyo.wd.utilities.math.Vector3i;

import javax.annotation.Nullable;

/**
 * Speaker peripheral: linked to a screen with the linker tool, it becomes a
 * positional audio source when the screen's sound mode is "speakers". Its
 * position relative to the viewing area (used for channel weighting) is set
 * by shift-right-clicking and dragging in the sound modeling map.
 */
public class SpeakerBlockEntity extends AbstractPeripheralBlockEntity {
    /** Relative position in the sound modeling map; both in [-1, 1], 0 = center. */
    private float relX = 0.0f; // -1 = left, +1 = right (from the audience's point of view)
    private float relY = 0.0f; // -1 = front (near screen), +1 = back

    public SpeakerBlockEntity(BlockPos pos, BlockState state) {
        super(TileRegistry.SPEAKER.get(), pos, state);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        relX = clamp(tag.getFloat("SpeakerRelX"));
        relY = clamp(tag.getFloat("SpeakerRelY"));
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putFloat("SpeakerRelX", relX);
        tag.putFloat("SpeakerRelY", relY);
    }

    // Sync the full state (link + relative position) to clients
    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public boolean connect(net.minecraft.world.level.Level world, BlockPos blockPos, BlockState blockState, Vector3i pos, BlockSide side) {
        boolean ok = super.connect(world, blockPos, blockState, pos, side);
        if (ok)
            sync();
        return ok;
    }

    public float getRelX() {
        return relX;
    }

    public float getRelY() {
        return relY;
    }

    /** Server-side: updates the modeling-map position and syncs to clients. */
    public void setRelPos(float x, float y) {
        relX = clamp(x);
        relY = clamp(y);
        setChanged();
        sync();
    }

    private void sync() {
        if (level != null && !level.isClientSide)
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    private static float clamp(float v) {
        return Math.max(-1.0f, Math.min(1.0f, v));
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide)
            ClientSpeakerRegistry.register(this);
    }

    @Override
    public void setRemoved() {
        if (level != null && level.isClientSide)
            ClientSpeakerRegistry.unregister(this);
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        if (level != null && level.isClientSide)
            ClientSpeakerRegistry.unregister(this);
        super.onChunkUnloaded();
    }
}
