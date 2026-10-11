package net.xmilon.craftpy.block;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.xmilon.craftpy.content.Defs;
import net.xmilon.craftpy.registry.SlotRegistry;

/**
 * One generic pre-registered item slot; name, tooltip, texture, stack size,
 * glow, food value and on-use behaviour are all driven by the current
 * {@link Defs.ItemDef}.
 */
public class SlottedItem extends Item {
    private final int slotIndex;

    public SlottedItem(int slotIndex, Properties properties) {
        super(properties);
        this.slotIndex = slotIndex;
    }

    public int slotIndex() {
        return slotIndex;
    }

    public Defs.ItemDef definition() {
        return SlotRegistry.itemDef(slotIndex);
    }

    @Override
    public Component getName(ItemStack stack) {
        Defs.ItemDef def = definition();
        if (def != null && def.name != null) {
            return Component.literal(def.name);
        }
        return Component.literal("Unloaded: " + SlotRegistry.holderForItem(slotIndex));
    }

    @Override
    public String getDescriptionId(ItemStack stack) {
        Defs.ItemDef def = definition();
        if (def != null && def.name != null && !def.name.isBlank()) {
            return def.name;
        }
        return "item.craftpy.unloaded";
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flags) {
        Defs.ItemDef def = definition();
        String holder = SlotRegistry.holderForItem(slotIndex);
        lines.add(Component.literal(holder).withStyle(ChatFormatting.DARK_GRAY));
        if (def == null) {
            lines.add(Component.literal("Unloaded - load the project to activate.").withStyle(ChatFormatting.GRAY));
        } else {
            if (def.food != null) {
                lines.add(Component.literal("Food: restores " + def.food.nutrition() + " hunger").withStyle(ChatFormatting.GRAY));
            }
            if (def.useEffect != null) {
                lines.add(Component.literal("Right-click: applies " + def.useEffect).withStyle(ChatFormatting.GRAY));
            }
        }
        super.appendHoverText(stack, context, lines, flags);
    }

    @Override
    public int getMaxStackSize(ItemStack stack) {
        Defs.ItemDef def = definition();
        return def != null ? def.maxStack : 64;
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        Defs.ItemDef def = definition();
        return (def != null && def.glow) || super.isFoil(stack);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand usedHand) {
        ItemStack stack = player.getItemInHand(usedHand);
        Defs.ItemDef def = definition();
        if (def != null && def.food != null) {
            if (player.canEat(def.food.canAlwaysEat())) {
                player.startUsingItem(usedHand);
                return InteractionResultHolder.consume(stack);
            }
            return InteractionResultHolder.fail(stack);
        }
        if (def != null && def.useEffect != null) {
            MobEffectInstance effect = resolveEffect(def.useEffect);
            if (effect != null) {
                player.addEffect(effect);
                return InteractionResultHolder.success(stack);
            }
        }
        return super.use(level, player, usedHand);
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        Defs.ItemDef def = definition();
        return def != null && def.food != null ? def.food.eatDurationTicks() : super.getUseDuration(stack, entity);
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        Defs.ItemDef def = definition();
        return def != null && def.food != null ? UseAnim.EAT : super.getUseAnimation(stack);
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        Defs.ItemDef def = definition();
        if (def != null && def.food != null) {
            return entity.eat(level, stack, def.food);
        }
        return super.finishUsingItem(stack, level, entity);
    }

    /** Parses "effect[:amplifier:seconds]" (id may be namespaced). Returns null on error. */
    private static MobEffectInstance resolveEffect(String spec) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        String[] parts = spec.split(":");
        String id = parts.length >= 3
                ? String.join(":", java.util.Arrays.copyOf(parts, parts.length - 2))
                : parts[0];
        int amplifier = parts.length >= 2 ? parsePositive(parts[parts.length - 2], 0) : 0;
        float seconds = parts.length >= 3 ? parsePositive(parts[parts.length - 1], 5.0f) : 5.0f;
        try {
            Holder.Reference<MobEffect> holder = BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.parse(id)).orElse(null);
            if (holder == null) {
                return null;
            }
            return new MobEffectInstance(holder, Math.max(1, Math.round(seconds * 20.0f)), amplifier, false, true, true);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int parsePositive(String raw, int fallback) {
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float parsePositive(String raw, float fallback) {
        try {
            return Math.max(0.0f, Float.parseFloat(raw.trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}