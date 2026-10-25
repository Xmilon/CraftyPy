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

    /**
     * Fills in the data components the definition lists (other mods' data: affixes, curio modifiers...) when a stack does
     * not have them yet, whatever made it (recipe, loot, give, creative tab, JEI). What a stack already has is kept.
     */
    @Override
    public void inventoryTick(ItemStack stack, Level level, net.minecraft.world.entity.Entity entity, int slot, boolean selected) {
        super.inventoryTick(stack, level, entity, slot, selected);
        Defs.ItemDef def = definition();
        if (level.isClientSide || def == null || def.components == null || def.components.isEmpty()) {
            return;
        }
        net.minecraft.core.component.DataComponentPatch patch = componentPatch(def, level.registryAccess());
        if (patch == null) {
            return;
        }
        for (var entry : patch.entrySet()) {
            if (entry.getValue().isPresent() && !stack.has(entry.getKey())) {
                applyOne(stack, entry.getKey(), entry.getValue().get());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void applyOne(ItemStack stack, net.minecraft.core.component.DataComponentType<T> type, Object value) {
        stack.set(type, (T) value);
    }

    private static final java.util.Map<String, net.minecraft.core.component.DataComponentPatch> PATCHES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** The components of a definition, parsed once per distinct JSON; null (and logged once) when they do not parse. */
    private static net.minecraft.core.component.DataComponentPatch componentPatch(Defs.ItemDef def,
                                                                                  net.minecraft.core.RegistryAccess registries) {
        String key = def.components.toString();
        var cached = PATCHES.get(key);
        if (cached != null) {
            return cached == net.minecraft.core.component.DataComponentPatch.EMPTY && !key.equals("{}") ? null : cached;
        }
        var result = net.minecraft.core.component.DataComponentPatch.CODEC.parse(
                registries.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE), def.components);
        var patch = result.resultOrPartial(error -> net.xmilon.craftpy.CraftPyLog.LOGGER.warn(
                "Item components of {} do not fit: {}", def.name, error)).orElse(net.minecraft.core.component.DataComponentPatch.EMPTY);
        PATCHES.put(key, patch);
        return patch == net.minecraft.core.component.DataComponentPatch.EMPTY ? null : patch;
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
        if (def != null && def.bow != null) {
            if (!player.getAbilities().instabuild && !def.bow.infinite() && findAmmo(player, def.bow).isEmpty()) {
                return InteractionResultHolder.fail(stack);
            }
            player.startUsingItem(usedHand);
            playSound(level, player, def.bow.drawSound(), 0.7f, 1f);
            return InteractionResultHolder.consume(stack);
        }
        if (def != null && def.thrown != null) {
            player.startUsingItem(usedHand);
            return InteractionResultHolder.consume(stack);
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
        if (def != null && (def.bow != null || def.thrown != null)) return 72000;
        return def != null && def.food != null ? def.food.eatDurationTicks() : super.getUseDuration(stack, entity);
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        Defs.ItemDef def = definition();
        if (def != null && def.bow != null) return UseAnim.BOW;
        if (def != null && def.thrown != null) return UseAnim.SPEAR;
        return def != null && def.food != null ? UseAnim.EAT : super.getUseAnimation(stack);
    }

    // ------------------------------------------------------------------
    // Bow(...) and Throwable(...): drawn while used, let go when the button is released
    // ------------------------------------------------------------------

    private static void playSound(Level level, LivingEntity at, String id, float volume, float pitch) {
        var sound = net.xmilon.craftpy.content.SoundIds.of(id);
        if (sound != null && !level.isClientSide) {
            level.playSound(null, at.getX(), at.getY(), at.getZ(), sound, net.minecraft.sounds.SoundSource.PLAYERS, volume, pitch);
        }
    }

    /** The first stack of the ammo (an item id or a #tag) in the offhand, then the inventory; empty when there is none. */
    private static ItemStack findAmmo(Player player, Defs.ItemDef.BowSpec bow) {
        java.util.function.Predicate<ItemStack> wanted;
        if (bow.ammo().startsWith("#")) {
            var tag = ResourceLocation.tryParse(bow.ammo().substring(1));
            wanted = tag == null ? stack -> false : stack -> stack.is(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, tag));
        } else {
            var id = ResourceLocation.tryParse(bow.ammo());
            wanted = id == null ? stack -> false : stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(id);
        }
        if (wanted.test(player.getOffhandItem())) return player.getOffhandItem();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack candidate = player.getInventory().getItem(slot);
            if (wanted.test(candidate)) return candidate;
        }
        return ItemStack.EMPTY;
    }

    /** How far a bow is drawn, 0 to 1, with vanilla's curve: the first half of the draw does little. */
    public static float drawn(int ticks, int full) {
        float f = ticks / (float) full;
        return Math.min(1f, (f * f + f * 2f) / 3f);
    }

    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remaining) {
        Defs.ItemDef def = definition();
        if (def == null || level.isClientSide) return;
        int used = getUseDuration(stack, entity) - remaining;
        if (def.bow != null && used == def.bow.drawTicks()) playSound(level, entity, def.bow.fullSound(), 0.8f, 1f);
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int remaining) {
        Defs.ItemDef def = definition();
        if (def == null || !(entity instanceof Player player) || level.isClientSide) return;
        int used = getUseDuration(stack, entity) - remaining;
        if (def.bow != null) {
            shoot(stack, level, player, def.bow, used);
        } else if (def.thrown != null) {
            hurl(stack, level, player, def.thrown, used);
        }
    }

    private void shoot(ItemStack stack, Level level, Player player, Defs.ItemDef.BowSpec bow, int used) {
        float pull = drawn(used, bow.drawTicks());
        if (pull < 0.1f) return; // a tap is not a shot
        boolean free = player.getAbilities().instabuild || bow.infinite();
        ItemStack ammo = findAmmo(player, bow);
        if (ammo.isEmpty() && !free) return;
        ItemStack arrowStack = ammo.isEmpty() ? new ItemStack(net.minecraft.world.item.Items.ARROW) : ammo.copyWithCount(1);
        net.minecraft.world.item.ArrowItem arrowItem = arrowStack.getItem() instanceof net.minecraft.world.item.ArrowItem a ? a
                : (net.minecraft.world.item.ArrowItem) net.minecraft.world.item.Items.ARROW;
        for (int n = 0; n < bow.arrows(); n++) {
            net.minecraft.world.entity.projectile.AbstractArrow arrow = arrowItem.createArrow(level, arrowStack, player, stack);
            float turn = (n - (bow.arrows() - 1) / 2f) * 7f;
            arrow.shootFromRotation(player, player.getXRot(), player.getYRot() + turn, 0f, pull * bow.power(), bow.spread() * (pull < 1f ? 1.5f : 1f));
            arrow.setBaseDamage(bow.damage() / bow.power());
            if (pull >= 1f) arrow.setCritArrow(true);
            if (free) arrow.pickup = net.minecraft.world.entity.projectile.AbstractArrow.Pickup.CREATIVE_ONLY;
            level.addFreshEntity(arrow);
        }
        String shot = bow.shootSound();
        if (shot.isEmpty()) {
            level.playSound(null, player.getX(), player.getY(), player.getZ(), net.minecraft.sounds.SoundEvents.ARROW_SHOOT,
                    net.minecraft.sounds.SoundSource.PLAYERS, 1f, 1f / (level.getRandom().nextFloat() * 0.4f + 1.2f) + pull * 0.5f);
        } else {
            playSound(level, player, shot, 1f, 0.9f + pull * 0.2f);
        }
        if (!free) {
            ammo.shrink(1);
            if (ammo.isEmpty()) player.getInventory().removeItem(ammo);
        }
        stack.hurtAndBreak(1, player, net.minecraft.world.entity.LivingEntity.getSlotForHand(player.getUsedItemHand()));
        player.awardStat(net.minecraft.stats.Stats.ITEM_USED.get(this));
    }

    private void hurl(ItemStack stack, Level level, Player player, Defs.ItemDef.ThrowSpec spec, int used) {
        if (used < 5) return;
        float charge = Math.min(1f, used / (float) spec.chargeTicks());
        var thrown = new net.xmilon.craftpy.entity.ThrownWeapon(level, player, stack, spec);
        thrown.shootFromRotation(player, player.getXRot(), player.getYRot(), 0f, spec.speed() * Math.max(0.4f, charge), 1f);
        if (player.getAbilities().instabuild) thrown.pickup = net.minecraft.world.entity.projectile.AbstractArrow.Pickup.CREATIVE_ONLY;
        level.addFreshEntity(thrown);
        String sound = spec.throwSound();
        if (sound.isEmpty()) {
            level.playSound(null, player.getX(), player.getY(), player.getZ(), net.minecraft.sounds.SoundEvents.TRIDENT_THROW.value(),
                    net.minecraft.sounds.SoundSource.PLAYERS, 1f, 1f);
        } else {
            playSound(level, player, sound, 1f, 0.95f + charge * 0.1f);
        }
        if (!player.getAbilities().instabuild) player.getInventory().removeItem(stack);
        player.awardStat(net.minecraft.stats.Stats.ITEM_USED.get(this));
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        Defs.ItemDef def = definition();
        if (def != null && def.food != null) {
            return entity.eat(level, stack, def.food);
        }
        return super.finishUsingItem(stack, level, entity);
    }


    /** Placeable: shift + right click on a block sets one of the stack down as a prop, data and all. */
    @Override
    public net.minecraft.world.InteractionResult useOn(net.minecraft.world.item.context.UseOnContext context) {
        Defs.ItemDef def = definition();
        Player player = context.getPlayer();
        if (def == null || def.prop == null || player == null || !player.isShiftKeyDown()) return super.useOn(context);
        Level level = context.getLevel();
        var pos = context.getClickLocation();
        var spot = new net.minecraft.world.phys.Vec3(pos.x, context.getClickedFace() == net.minecraft.core.Direction.DOWN
                ? pos.y - def.prop[1] : pos.y, pos.z);
        var box = net.minecraft.world.entity.EntityDimensions.scalable(def.prop[0], def.prop[1]).makeBoundingBox(spot);
        if (!level.noCollision(box)) return net.minecraft.world.InteractionResult.FAIL;
        if (!level.isClientSide) {
            var prop = net.xmilon.craftpy.entity.PropEntity.of(net.xmilon.craftpy.registry.ModEntities.PROP.get(), level,
                    context.getItemInHand(), spot, player.getYRot() + 180f, def.prop[0], def.prop[1]);
            level.addFreshEntity(prop);
            level.playSound(null, spot.x, spot.y, spot.z, net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_LEATHER.value(),
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.8f, 1f);
            context.getItemInHand().consume(1, player);
        }
        return net.minecraft.world.InteractionResult.sidedSuccess(level.isClientSide);
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
        // negative is an error in NeoForge 21.1 (JEI and furnaces threw for every CraftPy item): ask the default instead
        return def != null && def.burnTime > 0 ? def.burnTime * 20 : super.getBurnTime(stack, recipeType);
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
            Holder.Reference<MobEffect> holder = BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.parse(
                    net.xmilon.craftpy.registry.SlotRegistry.resolve(id, net.xmilon.craftpy.registry.SlotRegistry.Kind.EFFECT)))
                    .orElse(null);
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