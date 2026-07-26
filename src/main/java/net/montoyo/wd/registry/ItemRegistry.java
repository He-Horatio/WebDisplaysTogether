package net.montoyo.wd.registry;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.montoyo.wd.block.item.KeyboardItem;
import net.montoyo.wd.core.CraftComponent;
import net.montoyo.wd.item.*;

import java.util.Locale;

@SuppressWarnings({"unchecked", "unused"})
public class ItemRegistry {
    public static void init(IEventBus bus) {
        ITEMS.register(bus);
    }

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, "webdisplaystogether");

    protected static final RegistryObject<Item>[] COMP_CRAFT_ITEMS = new RegistryObject[CraftComponent.values().length];

    public static final RegistryObject<Item> CONFIGURATOR = ITEMS.register("screencfg", () -> new ItemScreenConfigurator(new Item.Properties()));
    public static final RegistryObject<Item> OWNERSHIP_THEIF = ITEMS.register("ownerthief", () -> new ItemOwnershipThief(new Item.Properties()));
    public static final RegistryObject<Item> LINKER = ITEMS.register("linker", () -> new ItemLinker(new Item.Properties()));
    public static final RegistryObject<Item> MINEPAD = ITEMS.register("minepad", () -> new ItemMinePad2(new Item.Properties()));
    public static final RegistryObject<Item> LASER_POINTER = ITEMS.register("laserpointer", () -> new ItemLaserPointer(new Item.Properties()));

    static {
        CraftComponent[] components = CraftComponent.values();
        for (int i = 0; i < components.length; i++) {
            CraftComponent cc = components[i];
            COMP_CRAFT_ITEMS[i] = ITEMS.register("craftcomp_" + cc.name().toLowerCase(Locale.ROOT), () -> new ItemCraftComponent(new Item.Properties()));
        }
    }

    public static final RegistryObject<Item> SCREEN = ITEMS.register("screen", () -> new BlockItem(BlockRegistry.SCREEN_BLOCk.get(), new Item.Properties()/*.tab(WebDisplays.CREATIVE_TAB)*/));

    public static final RegistryObject<Item> KEYBOARD = ITEMS.register("keyboard", () -> new KeyboardItem(BlockRegistry.KEYBOARD_BLOCK.get(), new Item.Properties()/*.tab(WebDisplays.CREATIVE_TAB)*/));
    public static final RegistryObject<Item> REMOTE_CONTROLLER = ITEMS.register("rctrl", () -> new BlockItem(BlockRegistry.REMOTE_CONTROLLER_BLOCK.get(), new Item.Properties()/*.tab(WebDisplays.CREATIVE_TAB)*/));
    public static final RegistryObject<Item> SPEAKER = ITEMS.register("speaker", () -> new BlockItem(BlockRegistry.SPEAKER_BLOCK.get(), new Item.Properties()));

    public static RegistryObject<Item> getComputerCraftItem(int index) {
        return COMP_CRAFT_ITEMS[index];
    }

    public static int countCompCraftItems() {
        return COMP_CRAFT_ITEMS.length;
    }

    public static boolean isCompCraftItem(Item item) {
        for (RegistryObject<Item> itemRegistryObject : COMP_CRAFT_ITEMS)
            if (item == itemRegistryObject.get())
                return true;
        return false;
    }
}
