package net.xmilon.craftpy.registry;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Independent startup pool: no existing block, item or mob indices are consumed or renumbered. */
public final class KineticSlots {
    private static final DeferredRegister.Blocks REGISTER = DeferredRegister.createBlocks("craftpy");
    private static final DeferredRegister<BlockEntityType<?>> TYPES = DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, "craftpy");
    public static final List<DeferredBlock<Block>> BLOCKS = new ArrayList<>();
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BlockEntity>> TYPE = TYPES.register("kinetic_slot",
            () -> BlockEntityType.Builder.of(KineticSlots::entity, BLOCKS.stream().map(DeferredBlock::get).toArray(Block[]::new)).build(null));
    static {
        for (int i = 0; i < SlotCounts.kinetics(); i++) {
            final int index = i;
            BLOCKS.add(REGISTER.register("pckinetic_" + index, () -> net.xmilon.craftpy.content.MachineBackend.selected().blocks().apply(index)));
        }
    }
    private KineticSlots() { }
    public static boolean available() { return net.xmilon.craftpy.project.RuntimeFeatures.createAvailable(); }
    private static BlockEntity entity(BlockPos pos, BlockState state) {
        return net.xmilon.craftpy.content.MachineBackend.selected().entities().apply(pos, state);
    }
    public static void register(IEventBus bus) {
        REGISTER.register(bus); TYPES.register(bus);
        bus.addListener((net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent event) -> {
            event.registerBlockEntity(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, TYPE.get(), (be, side) ->
                    be instanceof net.xmilon.craftpy.content.MachineAccess m ? m.machineState().automation(side) : null);
            event.registerBlockEntity(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK, TYPE.get(), (be, side) -> {
                if (!(be instanceof net.xmilon.craftpy.content.MachineAccess m)) return null;
                var state = m.machineState(); state.configure(); var def = state.definition();
                return (def != null && def.energy != null) || state.energy.getEnergyStored() > 0 ? state.energy : null;
            });
            event.registerBlockEntity(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK, TYPE.get(), (be, side) -> {
                if (!(be instanceof net.xmilon.craftpy.content.MachineAccess m)) return null;
                var state = m.machineState(); state.configure(); var def = state.definition();
                return (def != null && def.fluid != null) || !state.fluid.isEmpty() ? state.fluid : null;
            });
        });
    }
    public static int index(Block block) {
        var id = BuiltInRegistries.BLOCK.getKey(block);
        return id.getNamespace().equals("craftpy") && id.getPath().startsWith("pckinetic_") ? Integer.parseInt(id.getPath().substring(10)) : -1;
    }
    public static BlockState configuredState(Block block) {
        int index = index(block); var definition = index < 0 ? null : SlotRegistry.kineticDef(index);
        BlockState state = block.defaultBlockState();
        return definition != null && definition.kinetic != null ? state.setValue(BlockStateProperties.AXIS, definition.kinetic.axis()) : state;
    }
}
