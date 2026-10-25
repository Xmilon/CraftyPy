package net.xmilon.craftpy.registry;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.content.Defs;
import net.xmilon.craftpy.entity.SlottedEntity;

/**
 * The mob slot pools: {@code pcentity_N} (passive, CREATURE spawns) and {@code pcmonster_N} (hostile, MONSTER spawns),
 * registered once at startup like the block and item pools. The spawn egg is one item; the stack names the mob.
 */
public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, CraftPyLog.MODID);

    public static final List<DeferredHolder<EntityType<?>, EntityType<SlottedEntity>>> MOBS = new ArrayList<>();
    public static final List<DeferredHolder<EntityType<?>, EntityType<SlottedEntity>>> MONSTERS = new ArrayList<>();

    static {
        for (int i = 0; i < SlotRegistry.ENTITY_SLOTS; i++) {
            MOBS.add(register(SlotRegistry.Kind.ENTITY, i, MobCategory.CREATURE));
        }
        for (int i = 0; i < SlotRegistry.MONSTER_SLOTS; i++) {
            MONSTERS.add(register(SlotRegistry.Kind.MONSTER, i, MobCategory.MONSTER));
        }
    }

    /** Items set down in the world as themselves (Placeable): one type for every project, the stack says what it is. */
    public static final DeferredHolder<EntityType<?>, EntityType<net.xmilon.craftpy.entity.PropEntity>> PROP = ENTITY_TYPES.register("prop",
            () -> EntityType.Builder.<net.xmilon.craftpy.entity.PropEntity>of(net.xmilon.craftpy.entity.PropEntity::new, MobCategory.MISC)
                    .sized(0.6f, 0.6f).clientTrackingRange(10).updateInterval(20).build(CraftPyLog.MODID + ":prop"));

    /** A weapon in flight (Throwable): one type for every project. */
    public static final DeferredHolder<EntityType<?>, EntityType<net.xmilon.craftpy.entity.ThrownWeapon>> THROWN = ENTITY_TYPES.register("thrown",
            () -> EntityType.Builder.<net.xmilon.craftpy.entity.ThrownWeapon>of(net.xmilon.craftpy.entity.ThrownWeapon::new, MobCategory.MISC)
                    .sized(0.5f, 0.5f).clientTrackingRange(4).updateInterval(20).build(CraftPyLog.MODID + ":thrown"));

    private ModEntities() {
    }

    private static DeferredHolder<EntityType<?>, EntityType<SlottedEntity>> register(SlotRegistry.Kind kind, int index,
                                                                                     MobCategory category) {
        String name = SlotRegistry.slotName(new SlotRegistry.SlotRef(kind, index));
        // with GeckoLib installed every mob can carry a GeckoLib model; the class is only loaded then
        boolean gecko = ModList.get() != null && ModList.get().isLoaded("geckolib");
        return ENTITY_TYPES.register(name, () -> EntityType.Builder.<SlottedEntity>of((type, level) -> gecko
                        ? net.xmilon.craftpy.compat.geckolib.GeoSlottedEntity.create(type, level, kind, index)
                        : new SlottedEntity(type, level, kind, index), category)
                .sized(0.6f, 1.8f).clientTrackingRange(10).build(CraftPyLog.MODID + ":" + name));
    }

    public static void register(IEventBus modBus) {
        ENTITY_TYPES.register(modBus);
        modBus.addListener(ModEntities::attributes);
        modBus.addListener(ModEntities::placements);
    }

    /** The type of a mob slot. */
    public static EntityType<SlottedEntity> type(SlotRegistry.SlotRef slot) {
        return (slot.kind() == SlotRegistry.Kind.MONSTER ? MONSTERS : MOBS).get(slot.index()).get();
    }

    private static void attributes(EntityAttributeCreationEvent event) {
        // defaults only: every mob takes the values of its definition when it first ticks
        var supplier = Mob.createMobAttributes().add(Attributes.ATTACK_DAMAGE, 2.0).add(Attributes.MOVEMENT_SPEED, 0.25)
                .build();
        MOBS.forEach(type -> event.put(type.get(), supplier));
        MONSTERS.forEach(type -> event.put(type.get(), supplier));
    }

    private static void placements(RegisterSpawnPlacementsEvent event) {
        for (int i = 0; i < MOBS.size(); i++) {
            final int index = i;
            event.register(MOBS.get(i).get(), SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    (type, level, reason, pos, random) -> SlotRegistry.entityDef(SlotRegistry.Kind.ENTITY, index) != null
                            && level.getBlockState(pos.below()).is(BlockTags.ANIMALS_SPAWNABLE_ON)
                            && level.getRawBrightness(pos, 0) > 8,
                    RegisterSpawnPlacementsEvent.Operation.REPLACE);
        }
        for (int i = 0; i < MONSTERS.size(); i++) {
            final int index = i;
            event.register(MONSTERS.get(i).get(), SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    (type, level, reason, pos, random) -> SlotRegistry.entityDef(SlotRegistry.Kind.MONSTER, index) != null
                            && monsterCanSpawn(type, level, reason, pos, random),
                    RegisterSpawnPlacementsEvent.Operation.REPLACE);
        }
    }

    private static boolean monsterCanSpawn(EntityType<SlottedEntity> type, ServerLevelAccessor level, MobSpawnType reason,
                                           BlockPos pos, RandomSource random) {
        return level.getDifficulty() != Difficulty.PEACEFUL
                && (MobSpawnType.ignoresLightRequirements(reason) || Monster.isDarkEnoughToSpawn(level, pos, random))
                && Mob.checkMobSpawnRules(type, level, reason, pos, random);
    }

    /** A spawn egg for one mob: the egg item, the mob's type in its entity data, its name and colors. */
    public static ItemStack egg(SlotRegistry.SlotRef slot, Defs.EntityDef def, int count) {
        ItemStack stack = new ItemStack(ModItems.SPAWN_EGG.get(), count);
        CompoundTag entity = new CompoundTag();
        entity.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(type(slot)).toString());
        stack.set(DataComponents.ENTITY_DATA, CustomData.of(entity));
        CompoundTag colors = new CompoundTag();
        colors.putInt("craftpy_egg", def == null ? 0x3d8f5a : def.eggColor);
        colors.putInt("craftpy_egg_spots", def == null ? 0xc9e86b : def.eggSpots);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(colors));
        stack.set(DataComponents.ITEM_NAME, Component.literal((def == null ? "Mob" : def.name) + " Spawn Egg"));
        return stack;
    }
}
