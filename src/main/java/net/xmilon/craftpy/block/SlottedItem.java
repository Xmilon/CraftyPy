package net.xmilon.craftpy.block;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.crafting.RecipeType;
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
        // the id and the project's name are added like a mod item's (client/ContentTooltip)
        if (def == null) {
            lines.add(Component.literal("Unloaded - load the project to activate.").withStyle(ChatFormatting.GRAY));
        } else {
            if (def.food != null) {
                lines.add(Component.literal("Food: restores " + def.food.nutrition() + " hunger").withStyle(ChatFormatting.GRAY));
            }
            if (def.useEffect != null) {
                lines.add(Component.literal("Right-click: applies " + def.useEffect).withStyle(ChatFormatting.GRAY));
            }
            ChatFormatting color = ChatFormatting.getByName(def.tooltipColor);
            for (String line : def.tooltip) {
                lines.add(Component.literal(line).withStyle(color == null ? ChatFormatting.GRAY : color));
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
        if (def != null && def.armor != null && def.food == null) {
            EquipmentSlot slot = getEquipmentSlot(stack);
            Equipable worn = () -> slot; // not implemented by the class: only armor items may answer to Equipable
            return worn.swapWithEquipmentSlot(this, level, player, usedHand);
        }
        if (def != null && def.cooldownSeconds > 0 && !level.isClientSide) {
            player.getCooldowns().addCooldown(this, Math.round(def.cooldownSeconds * 20f));
        }
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


    // ------------------------------------------------------------------
    // Extensions: tools, durability, weapons, armor, fuel (data from Defs.ItemDef)
    // ------------------------------------------------------------------

    /** Tool kind of this item ("pickaxe"...), or "" when it is not a tool. */
    public String toolKind() {
        Defs.ItemDef def = definition();
        return def == null || def.tool == null ? "" : def.tool.kind();
    }

    /** Mining level of this item as a tool (0 wood/gold ... 4 netherite), -1 when it is not a tool. */
    public int toolLevel() {
        Defs.ItemDef def = definition();
        return def == null || def.tool == null ? -1 : def.tool.level();
    }

    private static boolean suits(String kind, BlockState state) {
        return switch (kind) {
            case "pickaxe" -> state.is(BlockTags.MINEABLE_WITH_PICKAXE);
            case "axe" -> state.is(BlockTags.MINEABLE_WITH_AXE);
            case "shovel" -> state.is(BlockTags.MINEABLE_WITH_SHOVEL);
            case "hoe" -> state.is(BlockTags.MINEABLE_WITH_HOE);
            case "sword" -> state.is(Blocks.COBWEB) || state.is(BlockTags.SWORD_EFFICIENT);
            default -> false;
        };
    }

    /** The mining level a block asks for, from the vanilla needs_*_tool tags. */
    private static int requiredLevel(BlockState state) {
        if (state.is(BlockTags.NEEDS_DIAMOND_TOOL)) {
            return 3;
        }
        if (state.is(BlockTags.NEEDS_IRON_TOOL)) {
            return 2;
        }
        return state.is(BlockTags.NEEDS_STONE_TOOL) ? 1 : 0;
    }

    @Override
    public float getDestroySpeed(ItemStack stack, BlockState state) {
        Defs.ItemDef def = definition();
        if (def == null || def.tool == null || !suits(def.tool.kind(), state)) {
            return super.getDestroySpeed(stack, state);
        }
        if (def.tool.kind().equals("sword")) {
            return state.is(Blocks.COBWEB) ? Math.max(15.0f, def.tool.speed()) : 1.5f;
        }
        return def.tool.speed();
    }

    /** Like vanilla swords: a sword or weapon does not break blocks in creative (it would make building impossible). */
    @Override
    public boolean canAttackBlock(BlockState state, Level level, BlockPos pos, Player player) {
        Defs.ItemDef def = definition();
        boolean weapon = def != null && (def.weapon != null || def.tool != null && "sword".equals(def.tool.kind()));
        return !(weapon && player.isCreative());
    }

    @Override
    public boolean isCorrectToolForDrops(ItemStack stack, BlockState state) {
        Defs.ItemDef def = definition();
        if (def == null || def.tool == null) {
            return super.isCorrectToolForDrops(stack, state);
        }
        return suits(def.tool.kind(), state) && def.tool.level() >= requiredLevel(state);
    }

    @Override
    public int getMaxDamage(ItemStack stack) {
        Defs.ItemDef def = definition();
        return def == null ? 0 : def.maxDamage;
    }

    @Override
    public boolean isDamageable(ItemStack stack) {
        return getMaxDamage(stack) > 0;
    }

    @Override
    public boolean isValidRepairItem(ItemStack stack, ItemStack repairCandidate) {
        Defs.ItemDef def = definition();
        if (def == null || def.repairWith.isBlank()) {
            return false;
        }
        return BuiltInRegistries.ITEM.getKey(repairCandidate.getItem()).toString().equals(
                def.repairWith.contains(":") ? def.repairWith : "minecraft:" + def.repairWith);
    }

    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        Defs.ItemDef def = definition();
        if (def != null && (def.tool != null || def.weapon != null) && getMaxDamage(stack) > 0) {
            stack.hurtAndBreak(1, attacker, EquipmentSlot.MAINHAND);
            return true;
        }
        return super.hurtEnemy(stack, target, attacker);
    }

    @Override
    public boolean mineBlock(ItemStack stack, Level level, BlockState state, BlockPos pos, LivingEntity miner) {
        Defs.ItemDef def = definition();
        if (def != null && def.tool != null && getMaxDamage(stack) > 0 && state.getDestroySpeed(level, pos) != 0.0F) {
            stack.hurtAndBreak(1, miner, EquipmentSlot.MAINHAND);
            return true;
        }
        return super.mineBlock(stack, level, state, pos, miner);
    }

    @Override
    public int getBurnTime(ItemStack stack, RecipeType<?> recipeType) {
        Defs.ItemDef def = definition();
        return def != null && def.burnTime > 0 ? def.burnTime * 20 : -1;
    }

    @Override
    public int getEnchantmentValue(ItemStack stack) {
        Defs.ItemDef def = definition();
        return def != null && def.enchantValue >= 0 ? def.enchantValue : super.getEnchantmentValue(stack);
    }

    @Override
    public EquipmentSlot getEquipmentSlot(ItemStack stack) {
        Defs.ItemDef def = definition();
        if (def == null || def.armor == null) {
            return null;
        }
        return switch (def.armor.slot()) {
            case "head" -> EquipmentSlot.HEAD;
            case "legs" -> EquipmentSlot.LEGS;
            case "feet" -> EquipmentSlot.FEET;
            default -> EquipmentSlot.CHEST;
        };
    }

    /** Parses "effect[:amplifier:seconds]" (id may be namespaced). Returns null on error. */
    private static MobEffectInstance resolveEffect(String spec) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        String[] parts = spec.split(":");
        if (parts.length == 2 && !parts[1].trim().matches("\\d+")) {
            parts = new String[]{spec}; // "mod:effect" written by an older worker: an id, not id:amplifier
        }
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