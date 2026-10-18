package net.xmilon.craftpy.block;

import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.BlockState;
import net.xmilon.craftpy.entity.SlottedEntity;
import net.xmilon.craftpy.network.CraftPyNetwork;
import net.xmilon.craftpy.network.ProjectAction;
import net.xmilon.craftpy.project.PyForgePaths;
import net.xmilon.craftpy.registry.SlotRegistry;
import net.xmilon.craftpy.server.ProjectRequestHandler;

/**
 * The Python Wrench: a coder's inspector. Right-click a block or a mob to see what it is (its id, its block state, the
 * project or module that adds it) with a click-to-copy line of Python for it. Sneak + right-click on content of one of
 * your projects opens that project in the IDE (for players who may use it).
 */
public class PythonWrenchItem extends Item {
    public PythonWrenchItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.SUCCESS; // the client swings; the server answers
        }
        BlockPos pos = context.getClickedPos();
        BlockState state = context.getLevel().getBlockState(pos);
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        String owner = null;
        String content = null;
        if (state.getBlock() instanceof SlottedBlock slotted) {
            String[] holder = SlotRegistry.holderForBlock(slotted.slotIndex()).split(":", 3);
            if (holder.length == 3) {
                owner = holder[1];
                content = holder[2];
            }
        }
        String code = "world.set_block(Pos(%d, %d, %d), \"%s\")".formatted(pos.getX(), pos.getY(), pos.getZ(),
                content != null ? owner + ":" + content : id);
        report(player, content != null ? owner + ":" + content : id, properties(state), owner, code);
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!(player instanceof ServerPlayer server)) {
            return InteractionResult.SUCCESS;
        }
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString();
        String owner = null;
        if (target instanceof SlottedEntity slotted) {
            String[] holder = slotted.holder().split(":", 3);
            if (holder.length == 3) {
                owner = holder[1];
                id = holder[1] + ":" + holder[2];
            }
        }
        String code = "world.spawn(\"%s\", Pos(%d, %d, %d))".formatted(id, target.getBlockX(), target.getBlockY(),
                target.getBlockZ());
        report(server, id, "health " + Math.round(target.getHealth()) + "/" + Math.round(target.getMaxHealth()), owner, code);
        return InteractionResult.CONSUME;
    }

    private static void report(ServerPlayer player, String what, String detail, String owner, String code) {
        boolean editor = ProjectRequestHandler.Caller.of(player).allowed();
        boolean project = owner != null && Files.isDirectory(PyForgePaths.projects().resolve(owner));
        if (player.isShiftKeyDown() && editor && project) {
            CraftPyNetwork.sendTo(player, ProjectAction.OPEN_IDE, true, "open", owner);
            return;
        }
        MutableComponent line = Component.literal(what).withStyle(ChatFormatting.GOLD);
        if (!detail.isEmpty()) {
            line.append(Component.literal(" " + detail).withStyle(ChatFormatting.GRAY));
        }
        if (owner != null) {
            line.append(Component.literal(project ? "  (project " + owner + ")" : "  (module " + owner + ")")
                    .withStyle(ChatFormatting.AQUA));
        }
        line.append(Component.literal("  [copy Python]").withStyle(style -> style.withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, code))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(code)))));
        player.sendSystemMessage(line);
    }

    /** "[facing=north, half=top]" or empty. */
    private static String properties(BlockState state) {
        if (state.getValues().isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("[");
        state.getValues().forEach((property, value) -> {
            if (out.length() > 1) {
                out.append(", ");
            }
            out.append(property.getName()).append('=').append(value.toString().toLowerCase(Locale.ROOT));
        });
        return out.append(']').toString();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("Right-click a block or mob: what it is, who adds it, and its Python")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Sneak + right-click your project's content: open its code")
                .withStyle(ChatFormatting.GRAY));
    }
}
