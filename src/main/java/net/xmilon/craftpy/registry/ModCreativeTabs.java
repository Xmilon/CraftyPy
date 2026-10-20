package net.xmilon.craftpy.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.xmilon.craftpy.CraftPyLog;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CraftPyLog.MODID);

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

    /**
     * The blocks and items of the running projects and modules. Item-list mods (JEI, EMI, REI) build their list from the
     * creative tabs, so without this tab they never saw CraftPy content or its recipes. Only loaded content is listed; the
     * unused slots stay hidden. Rebuilt after every project sync ({@code ClientPackSync}).
     */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> PROJECTS = TABS.register("projects",
            () -> CreativeModeTab.builder()
                    .title(Component.literal("CraftPy Projects"))
                    .withTabsBefore(MAIN.getId())
                    .icon(() -> ModItems.PYTHON_WRENCH.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        for (var block : SlotRegistry.slotBlocks()) {
                            if (block.definition() != null) {
                                output.accept(block.asItem());
                            }
                        }
                        for (var item : SlotRegistry.slotItems()) {
                            if (item.definition() != null) {
                                output.accept(item);
                            }
                        }
                    })
                    .build());

    private ModCreativeTabs() {
    }
}
