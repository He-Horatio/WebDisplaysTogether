/*
 * Copyright (C) 2018 BARBOTIN Nicolas
 */

package net.montoyo.wd.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public class WDTabs {
	public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, "webdisplaystogether");
	
	public static final RegistryObject<CreativeModeTab> EXAMPLE_TAB = TABS.register("main", () -> CreativeModeTab.builder()
			// Set name of tab to display
			.title(Component.translatable("itemGroup.webdisplaystogether"))
			// Set icon of creative tab
			.icon(() -> new ItemStack(ItemRegistry.SCREEN.get()))
			// Add default items to tab
			.displayItems((params, output) -> {
				// core items
				output.accept(ItemRegistry.SCREEN.get());
				output.accept(ItemRegistry.KEYBOARD.get());
				output.accept(ItemRegistry.LINKER.get());
				// remote control
				output.accept(ItemRegistry.REMOTE_CONTROLLER.get());
				// admin tools
				output.accept(ItemRegistry.OWNERSHIP_THEIF.get());
				// tool items
				output.accept(ItemRegistry.SPEAKER.get());
				output.accept(ItemRegistry.CONFIGURATOR.get());
				output.accept(ItemRegistry.MINEPAD.get());
				output.accept(ItemRegistry.LASER_POINTER.get());
				
				// cc
				for (int i = 0; i < ItemRegistry.countCompCraftItems(); i++) output.accept(ItemRegistry.getComputerCraftItem(i).get());
			})
			.build()
	);
	
	public static void init(IEventBus bus) {
		TABS.register(bus);
	}
}
