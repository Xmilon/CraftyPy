package net.xmilon.craftpy.content;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public final class MachineInteractions {
    private MachineInteractions() { }
    public static java.util.List<ItemStack> preserveResources(java.util.List<ItemStack> drops, net.minecraft.world.level.storage.loot.LootParams.Builder context) {
        var entity = context.getOptionalParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY);
        if (!(entity instanceof MachineAccess machine)) return drops;
        var state = machine.machineState();
        if (state.energy.getEnergyStored() == 0 && state.fluid.isEmpty()) return drops;
        for (ItemStack stack : drops) {
            if (!(stack.getItem() instanceof net.minecraft.world.item.BlockItem item) || item.getBlock() != entity.getBlockState().getBlock()) continue;
            var tag = new net.minecraft.nbt.CompoundTag(); state.save(tag, entity.getLevel().registryAccess());
            // The two item slots already drop separately; copying them into the block would duplicate inventory.
            tag.put("CraftPyInventory", new KineticInventory().serializeNBT(entity.getLevel().registryAccess()));
            tag.putString("id", "craftpy:kinetic_slot");
            stack.set(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA, net.minecraft.world.item.component.CustomData.of(tag));
            break;
        }
        return drops;
    }
    public static void drop(Level level, BlockPos pos) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof MachineAccess machine)
            for (int i = 0; i < 2; i++) Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), machine.machineState().inventory.getStackInSlot(i));
    }
    public static ItemInteractionResult insert(ItemStack stack, Level level, BlockPos pos, Player player, InteractionHand hand) {
        if (!player.mayBuild() || !(level.getBlockEntity(pos) instanceof MachineAccess machine)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        var state = machine.machineState(); state.configure();
        if (state.definition() != null && state.definition().fluid != null && net.neoforged.neoforge.fluids.FluidUtil.interactWithFluidHandler(player, hand, level, pos, null)) return ItemInteractionResult.sidedSuccess(level.isClientSide);
        if (!state.processing() || stack.is(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, net.minecraft.resources.ResourceLocation.parse("c:tools/wrench")))) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        var inventory = state.inventory;
        if (inventory.insertItem(0, stack, true).getCount() == stack.getCount()) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (!level.isClientSide) { var rest = inventory.insertItem(0, stack, false); if (!player.isCreative()) player.setItemInHand(hand, rest); machine.syncMachine(); }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }
    /** Right click without a held item (or with one that did not act): the Screen(...) window, else take the output. */
    public static InteractionResult use(Level level, BlockPos pos, Player player, net.minecraft.world.level.block.Block block) {
        var def = net.xmilon.craftpy.registry.SlotRegistry.kineticDef(net.xmilon.craftpy.registry.KineticSlots.index(block));
        if (def != null && def.screen != null && level.getBlockEntity(pos) instanceof MachineAccess machine) {
            if (!level.isClientSide && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                var screen = def.screen;
                String title = screen.has("title") && !screen.get("title").isJsonNull() ? screen.get("title").getAsString() : def.name;
                boolean energy = (!screen.has("energy") || screen.get("energy").getAsBoolean()) && def.energy != null;
                boolean fluid = (!screen.has("fluid") || screen.get("fluid").getAsBoolean()) && def.fluid != null;
                boolean slots = !screen.has("slots") || screen.get("slots").getAsBoolean();
                var standard = net.xmilon.craftpy.menu.ScreenLayout.defaults(slots, energy, fluid);
                // a window drawn in the UI maker (Screen(layout=...)) replaces the standard one
                var layout = screen.has("layout") && screen.get("layout").isJsonObject()
                        ? net.xmilon.craftpy.menu.ScreenLayout.parse(screen.getAsJsonObject("layout"), standard) : standard;
                String layoutJson = layout.toJson().toString();
                if (layoutJson.length() > net.xmilon.craftpy.menu.ScreenLayout.MAX_JSON) {
                    layout = standard;
                    layoutJson = standard.toJson().toString();
                }
                var finalLayout = layout;
                String sent = layoutJson;
                serverPlayer.openMenu(new net.minecraft.world.SimpleMenuProvider((id, inventory, p) -> new net.xmilon.craftpy.menu.MachineMenu(
                        id, inventory, pos, machine, title, finalLayout), net.minecraft.network.chat.Component.literal(title)),
                        buf -> { buf.writeBlockPos(pos); buf.writeUtf(title, 64); buf.writeUtf(sent, net.xmilon.craftpy.menu.ScreenLayout.MAX_JSON + 100); });
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return extract(level, pos, player);
    }

    /** The hitbox the project declared (Block(shape=...) or taken from its model); null = the block's own. */
    public static net.minecraft.world.phys.shapes.VoxelShape shape(net.minecraft.world.level.block.Block block) {
        var def = net.xmilon.craftpy.registry.SlotRegistry.kineticDef(net.xmilon.craftpy.registry.KineticSlots.index(block));
        return def == null ? null : def.shape;
    }

    public static InteractionResult extract(Level level, BlockPos pos, Player player) {
        if (!player.mayBuild() || !(level.getBlockEntity(pos) instanceof MachineAccess machine)) return InteractionResult.PASS;
        if (!level.isClientSide) { var stack = machine.machineState().inventory.extractItem(player.isShiftKeyDown() ? 0 : 1, 64, false); if (!player.addItem(stack)) player.drop(stack, false); machine.syncMachine(); }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
