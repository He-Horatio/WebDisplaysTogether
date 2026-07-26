/*
 * Copyright (C) 2019 BARBOTIN Nicolas
 */

package net.montoyo.wd.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.network.PacketDistributor;
import net.montoyo.wd.core.DefaultPeripheral;
import net.montoyo.wd.entity.AbstractInterfaceBlockEntity;
import net.montoyo.wd.entity.AbstractPeripheralBlockEntity;
import net.montoyo.wd.item.ItemLinker;
import net.montoyo.wd.net.WDNetworkRegistry;
import net.montoyo.wd.net.client_bound.S2CMessageCloseGui;
import net.montoyo.wd.utilities.Log;
import org.jetbrains.annotations.Nullable;

public class PeripheralBlock extends WDContainerBlock {
    DefaultPeripheral type;

    public PeripheralBlock(DefaultPeripheral type) {
        super(BlockBehaviour.Properties.copy(Blocks.STONE).strength(1.5f, 10.f));
        this.type = type;
    }

    /** For subclasses that provide their own block entity (see SpeakerBlock). */
    protected PeripheralBlock(BlockBehaviour.Properties props) {
        super(props);
        this.type = null;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        BlockEntityType.BlockEntitySupplier<? extends BlockEntity> cls = type.getTEClass();
        if (cls == null)
            return null;

        try {
            return cls.create(pos, state);
        } catch (Throwable t) {
            Log.errorEx("Couldn't instantiate peripheral TileEntity:", t);
        }

        return null;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public InteractionResult use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (player.isShiftKeyDown())
            return InteractionResult.FAIL;

        if (player.getItemInHand(hand).getItem() instanceof ItemLinker)
            return InteractionResult.FAIL;

        BlockEntity te = world.getBlockEntity(pos);

        if (te instanceof AbstractPeripheralBlockEntity)
            return ((AbstractPeripheralBlockEntity) te).onRightClick(player, hand);
        else
            return InteractionResult.FAIL;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.block();
    }

    @Override
    public void setPlacedBy(Level world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (world.isClientSide)
            return;

        if (placer instanceof Player) {
            BlockEntity te = world.getBlockEntity(pos);

            if (te instanceof AbstractInterfaceBlockEntity)
                ((AbstractInterfaceBlockEntity) te).setOwner((Player) placer);
        }
    }

    @Override
    public PushReaction getPistonPushReaction(BlockState state) {
        return PushReaction.IGNORE;
    }

    @Override
    public void neighborChanged(BlockState state, Level world, BlockPos pos, Block neighborType, BlockPos neighbor, boolean isMoving) {
        BlockEntity te = world.getBlockEntity(pos);
        if (te instanceof AbstractPeripheralBlockEntity)
            ((AbstractPeripheralBlockEntity) te).onNeighborChange(neighborType, neighbor);
    }

    @Override
    public void playerDestroy(Level world, Player player, BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity, ItemStack tool) {
        if (!world.isClientSide) {
            WDNetworkRegistry.INSTANCE.send(PacketDistributor.NEAR.with(() -> point(world, pos)), new S2CMessageCloseGui(pos));
        }
        super.playerDestroy(world, player, pos, state, blockEntity, tool);
    }

    @Override
    public void onBlockExploded(BlockState state, Level level, BlockPos pos, Explosion explosion) {
        playerDestroy(level, null, pos, level.getBlockState(pos), null, null);
    }

    public static PacketDistributor.TargetPoint point(Player exclude, Level world, BlockPos bp) {
        return new PacketDistributor.TargetPoint((ServerPlayer) exclude, bp.getX(), bp.getY(), bp.getZ(), 64.0, world.dimension());
    }

    public static PacketDistributor.TargetPoint point(Level world, BlockPos bp) {
        return new PacketDistributor.TargetPoint(bp.getX(), bp.getY(), bp.getZ(), 64.0, world.dimension());
    }

}
