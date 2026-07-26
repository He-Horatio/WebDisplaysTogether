package net.montoyo.wd.registry;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.montoyo.wd.block.KeyboardBlockLeft;
import net.montoyo.wd.block.KeyboardBlockRight;
import net.montoyo.wd.block.PeripheralBlock;
import net.montoyo.wd.block.ScreenBlock;
import net.montoyo.wd.block.SpeakerBlock;
import net.montoyo.wd.core.DefaultPeripheral;

public class BlockRegistry {
    public static void init(IEventBus bus) {
        BLOCKS.register(bus);
    }

    public static DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, "webdisplaystogether");

    public static final RegistryObject<ScreenBlock> SCREEN_BLOCk = BLOCKS.register("screen", () -> new ScreenBlock(BlockBehaviour.Properties.copy(Blocks.STONE)));

    public static final RegistryObject<KeyboardBlockLeft> KEYBOARD_BLOCK = BlockRegistry.BLOCKS.register("kb_left", KeyboardBlockLeft::new);
    public static final RegistryObject<KeyboardBlockRight> blockKbRight = BLOCKS.register("kb_right", KeyboardBlockRight::new);

    public static final RegistryObject<PeripheralBlock> REMOTE_CONTROLLER_BLOCK = BlockRegistry.BLOCKS.register("rctrl", () -> new PeripheralBlock(DefaultPeripheral.REMOTE_CONTROLLER));

    public static final RegistryObject<SpeakerBlock> SPEAKER_BLOCK = BLOCKS.register("speaker", SpeakerBlock::new);
}
