package net.xmilon.craftpy.client;

import java.util.List;
import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.xmilon.craftpy.block.SlottedBlock;
import net.xmilon.craftpy.block.SlottedItem;
import net.xmilon.craftpy.registry.SlotRegistry;

/**
 * Makes a CraftPy block or item read like any mod's: the advanced tooltip (F3+H) shows {@code ruby_rush:ruby} instead of
 * the slot {@code craftpy:pcitem_4}, the project's name closes the tooltip in blue italics where mods put theirs, and one
 * line says it was made with CraftPy.
 */
public final class ContentTooltip {
    private ContentTooltip() {
    }

    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        String holder = holderOf(stack);
        String[] parts = holder == null ? null : split(holder);
        if (parts == null) {
            return;
        }
        List<Component> lines = event.getToolTip();
        String slotId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).getString().equals(slotId)) {
                lines.set(i, Component.literal(parts[0] + ":" + parts[1]).withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        lines.add(Component.literal(ownerName(parts[0])).withStyle(ChatFormatting.BLUE, ChatFormatting.ITALIC));
        lines.add(Component.literal("✦ Created with ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal("CraftPy").withStyle(ChatFormatting.GREEN)));
    }

    private static String holderOf(ItemStack stack) {
        if (stack.getItem() instanceof SlottedItem item) {
            return SlotRegistry.holderForItem(item.slotIndex());
        }
        if (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof SlottedBlock block) {
            return SlotRegistry.holderForBlock(block.slotIndex());
        }
        if (stack.getItem() instanceof BlockItem blockItem) {
            int index = net.xmilon.craftpy.registry.KineticSlots.index(blockItem.getBlock());
            if (index >= 0) return SlotRegistry.holderFor(SlotRegistry.Kind.KINETIC, index);
        }
        return null;
    }

    /** {@code ITEM:ruby_rush:ruby} is {@code [ruby_rush, ruby]}; null for a slot no project uses ({@code item_4}). */
    static String[] split(String holder) {
        String[] parts = holder.split(":", 3);
        return parts.length == 3 && !parts[1].isBlank() && !parts[2].isBlank() ? new String[]{parts[1], parts[2]} : null;
    }

    /** The project's or module's display name; its id in title case when neither is known here (a remote server). */
    static String ownerName(String id) {
        try {
            var manager = net.xmilon.craftpy.project.ProjectManager.get();
            var project = manager == null ? null : manager.getProject(id);
            if (project != null && project.metadata() != null && project.metadata().name != null
                    && !project.metadata().name.isBlank()) {
                return project.metadata().name;
            }
            for (var module : net.xmilon.craftpy.project.ModuleLoader.loadedModules()) {
                if (id.equals(module.id) && module.name != null && !module.name.isBlank()) {
                    return module.name;
                }
            }
        } catch (RuntimeException ignored) {
            // the name is decoration: fall back to the id
        }
        StringBuilder out = new StringBuilder();
        for (String word : id.split("_")) {
            if (!word.isEmpty()) {
                out.append(out.length() == 0 ? "" : " ").append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
            }
        }
        return out.toString();
    }
}
