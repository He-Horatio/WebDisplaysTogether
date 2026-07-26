package net.montoyo.wd.serverbrowser;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.montoyo.wd.utilities.data.BlockSide;

/**
 * Uniquely identifies one screen surface in the world: dimension + multiblock origin + side.
 */
public record ScreenKey(ResourceLocation dimension, BlockPos pos, BlockSide side) {
    public static ScreenKey of(Level level, BlockPos pos, BlockSide side) {
        return new ScreenKey(level.dimension().location(), pos.immutable(), side);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dimension);
        buf.writeBlockPos(pos);
        buf.writeByte(side.ordinal());
    }

    public static ScreenKey read(FriendlyByteBuf buf) {
        return new ScreenKey(buf.readResourceLocation(), buf.readBlockPos(), BlockSide.values()[buf.readByte()]);
    }

    @Override
    public String toString() {
        return dimension + "@" + pos.toShortString() + "/" + side;
    }
}
