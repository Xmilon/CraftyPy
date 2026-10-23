package net.xmilon.craftpy.registry;

import net.minecraft.world.inventory.MenuType;
import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.menu.ComputerMenu;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.Registries;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, CraftPyLog.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<ComputerMenu>> COMPUTER =
            MENUS.register("computer", () -> IMenuTypeExtension.create(ComputerMenu::new));

    /** The Minecraft-style window of machine blocks that declared Screen(...). */
    public static final DeferredHolder<MenuType<?>, MenuType<net.xmilon.craftpy.menu.MachineMenu>> MACHINE =
            MENUS.register("machine", () -> IMenuTypeExtension.create(net.xmilon.craftpy.menu.MachineMenu::new));

    private ModMenus() {
    }
}
