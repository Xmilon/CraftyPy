package net.xmilon.craftpy.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.pathfinder.PathType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.SoundActions;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.content.Defs;

/**
 * The liquid pool ({@code pcfluid_N}): per slot a fluid type, a source and a flowing fluid, a liquid block and a bucket,
 * registered once at startup like the other slot pools. A project's {@code Fluid(...)} fills a slot; what it declares
 * (density, viscosity, temperature, light, color, effects) is read live from the slot's definition, so it can change
 * without a restart. An unloaded slot is plain water-like and harmless.
 */
public final class FluidSlots {
    private static final DeferredRegister<FluidType> TYPES = DeferredRegister.create(NeoForgeRegistries.Keys.FLUID_TYPES, CraftPyLog.MODID);
    private static final DeferredRegister<Fluid> FLUIDS = DeferredRegister.create(Registries.FLUID, CraftPyLog.MODID);
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CraftPyLog.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CraftPyLog.MODID);

    public static final List<Supplier<? extends Fluid>> SOURCES = new ArrayList<>();
    public static final List<Supplier<? extends Item>> BUCKETS = new ArrayList<>();
    private static final List<Supplier<? extends FluidType>> TYPE_HOLDERS = new ArrayList<>();

    static {
        for (int i = 0; i < SlotCounts.fluids(); i++) {
            final int index = i;
            var type = TYPES.register("pcfluid_" + index, () -> new SlottedFluidType(index));
            var holder = new BaseFlowingFluid.Properties[1];
            var source = FLUIDS.register("pcfluid_" + index, () -> new BaseFlowingFluid.Source(holder[0]));
            var flowing = FLUIDS.register("pcfluid_flow_" + index, () -> new BaseFlowingFluid.Flowing(holder[0]));
            var block = BLOCKS.register("pcfluid_block_" + index, () -> new SlottedLiquidBlock(index, flowing.get()));
            var bucket = ITEMS.register("pcfluid_bucket_" + index, () -> new SlottedBucket(index, source.get()));
            holder[0] = new BaseFlowingFluid.Properties(type, source, flowing).block(block).bucket(bucket);
            SOURCES.add(source);
            TYPE_HOLDERS.add(type);
            BUCKETS.add(bucket);
        }
    }

    private FluidSlots() {
    }

    public static void register(IEventBus bus) {
        bus.addListener((net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent event) -> {
            for (var bucket : BUCKETS) {
                event.registerItem(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.ITEM,
                        (stack, context) -> new net.neoforged.neoforge.fluids.capability.wrappers.FluidBucketWrapper(stack), bucket.get());
            }
        });
        TYPES.register(bus);
        FLUIDS.register(bus);
        BLOCKS.register(bus);
        ITEMS.register(bus);
    }

    /** The fluid type of a slot (client code asks for it when it registers how the liquid looks). */
    public static List<Supplier<? extends FluidType>> types() {
        return List.copyOf(TYPE_HOLDERS);
    }

    public static Defs.FluidDef def(int index) {
        return SlotRegistry.fluidDef(index);
    }

    /** What the project declared for density, viscosity and the rest; water-like while the slot is unloaded. */
    public static final class SlottedFluidType extends FluidType {
        private final int index;

        SlottedFluidType(int index) {
            super(FluidType.Properties.create().descriptionId("fluid_type.craftpy.pcfluid_" + index)
                    .canSwim(true).canDrown(true).canExtinguish(false).supportsBoating(true)
                    .pathType(PathType.WATER).adjacentPathType(null)
                    .sound(SoundActions.BUCKET_FILL, net.minecraft.sounds.SoundEvents.BUCKET_FILL)
                    .sound(SoundActions.BUCKET_EMPTY, net.minecraft.sounds.SoundEvents.BUCKET_EMPTY));
            this.index = index;
        }

        @Override
        public int getDensity() {
            var def = def(index);
            return def == null ? 1000 : def.density;
        }

        @Override
        public int getViscosity() {
            var def = def(index);
            return def == null ? 1000 : def.viscosity;
        }

        @Override
        public int getTemperature() {
            var def = def(index);
            return def == null ? 300 : def.temperature;
        }

        @Override
        public int getLightLevel() {
            var def = def(index);
            return def == null ? 0 : def.light;
        }

        @Override
        public Component getDescription() {
            var def = def(index);
            return def == null ? Component.literal("Unloaded liquid") : Component.literal(def.name);
        }

        @Override
        public Component getDescription(net.neoforged.neoforge.fluids.FluidStack stack) {
            return getDescription();
        }
    }

    /** The liquid block: touching it applies the declared effects. */
    public static final class SlottedLiquidBlock extends LiquidBlock {
        private final int index;

        SlottedLiquidBlock(int index, net.minecraft.world.level.material.FlowingFluid fluid) {
            super(fluid, BlockBehaviour.Properties.of().mapColor(MapColor.WATER).replaceable().noCollission().strength(100.0F)
                    .pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY).noLootTable().liquid()
                    .sound(net.minecraft.world.level.block.SoundType.EMPTY));
            this.index = index;
        }

        @Override
        protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
            if (!level.isClientSide && entity instanceof LivingEntity living) {
                var def = def(index);
                if (def != null) {
                    applyEffects(living, def);
                }
            }
        }
    }

    static void applyEffects(LivingEntity living, Defs.FluidDef def) {
        for (Defs.FluidDef.Touch touch : def.effects) {
            var holder = BuiltInRegistries.MOB_EFFECT.getHolder(touch.effect());
            if (holder.isEmpty()) {
                continue;
            }
            var current = living.getEffect(holder.get());
            if (current == null || current.getAmplifier() < touch.amplifier() || current.getDuration() < 20) {
                living.addEffect(new MobEffectInstance(holder.get(), Math.max(20, touch.seconds() * 20), touch.amplifier(), false, true));
            }
        }
    }

    /** The bucket of a slot: named after the liquid. */
    public static final class SlottedBucket extends BucketItem {
        private final int index;

        SlottedBucket(int index, Fluid fluid) {
            super(fluid, new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1));
            this.index = index;
        }

        @Override
        public Component getName(ItemStack stack) {
            var def = def(index);
            return Component.literal(def == null ? "Unloaded bucket" : def.name + " Bucket");
        }
    }
}
