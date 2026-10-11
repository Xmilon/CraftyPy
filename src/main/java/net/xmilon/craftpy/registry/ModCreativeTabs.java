package net.xmilon.craftpy.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.xmilon.craftpy.CraftPy;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CraftPy.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.craftpy"))
                    .withTabsBefore(CreativeModeTabs.REDSTONE_BLOCKS)
                    .icon(() -> ModItems.PYTHON_COMPUTER.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.PYTHON_COMPUTER.get());
                        output.accept(ModItems.UNLOADED_BLOCK.get());
                        output.accept(ModItems.PYTHON_WRENCH.get());
                    })
                    .build());

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> SLOTS = TABS.register("slots",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.craftpy.slots"))
                    .withTabsAfter(net.minecraft.resources.ResourceKey.create(
                            Registries.CREATIVE_MODE_TAB,
                            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(CraftPy.MODID, "main")))
                    .icon(() -> ModItems.SLOT_BLOCK_ITEMS.get(0).get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        for (var item : ModItems.SLOT_BLOCK_ITEMS) {
                            output.accept(item.get());
                        }
                        for (var item : ModItems.SLOT_ITEMS) {
                            output.accept(item.get());
                        }
                    })
                    .build());

    private ModCreativeTabs() {
    }
}
