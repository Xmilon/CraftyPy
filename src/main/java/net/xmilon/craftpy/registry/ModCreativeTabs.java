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

    // NOTE: project content (the generic slot pool) deliberately has NO creative
    // tab and cannot be obtained with /give - it is exclusively distributed via
    // /craftpy give <player> <project>:<id> <block|item> (namespace isolation).

    private ModCreativeTabs() {
    }
}
