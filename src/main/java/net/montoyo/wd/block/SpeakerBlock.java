package net.montoyo.wd.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.montoyo.wd.data.SpeakerData;
import net.montoyo.wd.entity.SpeakerBlockEntity;
import net.montoyo.wd.item.ItemLinker;
import net.montoyo.wd.utilities.serialization.Util;

import javax.annotation.Nullable;

/**
 * Speaker: looks exactly like a note block, links to a screen with the linker
 * tool and plays that screen's audio when its sound mode is "speakers".
 * Shift-right-click opens the sound modeling map.
 */
public class SpeakerBlock extends PeripheralBlock {
    public SpeakerBlock() {
        super(BlockBehaviour.Properties.copy(Blocks.NOTE_BLOCK));
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SpeakerBlockEntity(pos, state);
    }

    @Override
    public InteractionResult use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (player.getItemInHand(hand).getItem() instanceof ItemLinker)
            return InteractionResult.FAIL; // handled by the linker item

        if (!player.isShiftKeyDown())
            return super.use(state, world, pos, player, hand, hit);

        // Shift-right-click: open the sound modeling map
        if (world.isClientSide)
            return InteractionResult.SUCCESS;

        BlockEntity te = world.getBlockEntity(pos);
        if (!(te instanceof SpeakerBlockEntity speaker))
            return InteractionResult.FAIL;

        if (!speaker.isLinked() || speaker.getConnectedScreen() == null) {
            Util.toast(player, "notLinked");
            return InteractionResult.SUCCESS;
        }

        if (player instanceof ServerPlayer sp)
            new SpeakerData(pos, speaker.getRelX(), speaker.getRelY()).sendTo(sp);

        return InteractionResult.SUCCESS;
    }
}
