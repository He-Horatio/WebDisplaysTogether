package net.montoyo.wd.data;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.montoyo.wd.client.gui.GuiSpeakerConfig;

/**
 * Server -> client GUI payload for the speaker's sound modeling map.
 */
public class SpeakerData extends GuiData {
    public BlockPos pos;
    public float relX, relY;

    public SpeakerData() {
        super();
    }

    public SpeakerData(BlockPos pos, float relX, float relY) {
        this.pos = pos;
        this.relX = relX;
        this.relY = relY;
    }

    @OnlyIn(Dist.CLIENT)
    @Override
    public Screen createGui(Screen old, Level world) {
        return new GuiSpeakerConfig(pos, relX, relY);
    }

    @Override
    public String getName() {
        return "Speaker";
    }

    @Override
    public void serialize(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeFloat(relX);
        buf.writeFloat(relY);
    }

    @Override
    public void deserialize(FriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        relX = buf.readFloat();
        relY = buf.readFloat();
    }
}
