package net.xmilon.craftpy.menu;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.xmilon.craftpy.registry.ModMenus;

public class ComputerMenu extends AbstractContainerMenu {
    public ComputerMenu(int containerId, Inventory playerInventory) {
        super(ModMenus.COMPUTER.get(), containerId);
    }

    public ComputerMenu(int containerId, Inventory playerInventory, FriendlyByteBuf data) {
        this(containerId, playerInventory);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
