package net.xmilon.craftpy.content;

import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/** Finite, native NeoForge operations; no Java reflection, per-frame Python, or chunk loading. */
public final class ResourceAccess {
    private ResourceAccess() { }
    public static JsonObject snapshot(ServerLevel level, JsonObject json) {
        JsonObject request = new JsonObject(); JsonArray xyz = new JsonArray();
        for (String key : java.util.List.of("x", "y", "z")) xyz.add(json.get(key));
        request.add("position", xyz); return snapshot(level, position(request, "position"), side(json,"side"));
    }
    public static JsonObject snapshot(ServerLevel level, BlockPos pos, Direction side) {
        check(level, pos); JsonObject out = new JsonObject(); out.addProperty("ok", true);
        var items = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
        if (items != null) {
            JsonArray slots = new JsonArray();
            for (int i = 0; i < Math.min(256, items.getSlots()); i++) {
                var stack = items.getStackInSlot(i); JsonObject slot = new JsonObject(); slot.addProperty("slot", i);
                slot.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()); slot.addProperty("count", stack.getCount()); slots.add(slot);
            }
            out.add("items", slots); out.addProperty("item_slots", items.getSlots());
        }
        var energy = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, side);
        if (energy != null) { out.addProperty("energy", energy.getEnergyStored()); out.addProperty("energy_capacity", energy.getMaxEnergyStored()); }
        var fluid = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, side);
        if (fluid != null) {
            JsonArray tanks = new JsonArray();
            for (int i = 0; i < Math.min(64, fluid.getTanks()); i++) {
                var stack = fluid.getFluidInTank(i); JsonObject tank = new JsonObject(); tank.addProperty("tank", i);
                tank.addProperty("fluid", BuiltInRegistries.FLUID.getKey(stack.getFluid()).toString()); tank.addProperty("amount", stack.getAmount());
                tank.addProperty("capacity", fluid.getTankCapacity(i)); tanks.add(tank);
            }
            out.add("fluids", tanks);
        }
        return out;
    }
    public static Direction side(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) return null;
        var dir = Direction.byName(json.get(key).getAsString());
        if (dir == null) throw new IllegalArgumentException("Unknown capability face: " + key);
        return dir;
    }
    private static BlockPos position(JsonObject json, String key) {
        var value = json.getAsJsonArray(key);
        if (value.size() != 3) throw new IllegalArgumentException(key + " needs three coordinates");
        int[] xyz = new int[3];
        for (int i = 0; i < 3; i++) {
            double number = value.get(i).getAsDouble();
            if (!Double.isFinite(number) || number != Math.rint(number) || Math.abs(number) > 30_000_000) throw new IllegalArgumentException("Invalid block coordinate");
            xyz[i] = (int) number;
        }
        return new BlockPos(xyz[0], xyz[1], xyz[2]);
    }
    private static void check(ServerLevel level, BlockPos pos) {
        if (level == null || !level.isInWorldBounds(pos) || !level.getWorldBorder().isWithinBounds(pos) || !level.hasChunkAt(pos)) throw new IllegalArgumentException("Resource position must be within a loaded world chunk");
    }
    public static JsonObject transfer(ServerLevel level, JsonObject json) {
        BlockPos source = position(json, "source"), target = position(json, "target"); check(level, source); check(level, target);
        if (source.equals(target)) throw new IllegalArgumentException("Resource transfers need different blocks");
        double requested = json.get("amount").getAsDouble();
        if (!Double.isFinite(requested) || requested != Math.rint(requested) || requested < 1 || requested > 1_000_000) throw new IllegalArgumentException("Transfer amount must be 1..1000000");
        int amount = (int) requested, moved;
        boolean simulate = Defs.b(json, "simulate", false);
        Direction from = side(json, "source_side"), to = side(json, "target_side");
        switch (Defs.s(json, "kind", "item")) {
            case "energy" -> {
                var a = level.getCapability(Capabilities.EnergyStorage.BLOCK, source, from); var b = level.getCapability(Capabilities.EnergyStorage.BLOCK, target, to);
                if (a == null || b == null) throw new IllegalArgumentException("Both faces need energy capabilities");
                moved = b.receiveEnergy(a.extractEnergy(amount, true), true);
                if (!simulate && moved > 0) {
                    int extracted = a.extractEnergy(moved, false); int received = b.receiveEnergy(extracted, false);
                    if (received != extracted) throw new IllegalStateException("Energy handler violated its simulation result");
                    moved = received;
                }
            }
            case "fluid" -> {
                var a = level.getCapability(Capabilities.FluidHandler.BLOCK, source, from); var b = level.getCapability(Capabilities.FluidHandler.BLOCK, target, to);
                if (a == null || b == null) throw new IllegalArgumentException("Both faces need fluid capabilities");
                var stack = a.drain(amount, IFluidHandler.FluidAction.SIMULATE); moved = b.fill(stack, IFluidHandler.FluidAction.SIMULATE);
                if (!simulate && moved > 0) {
                    var extracted = a.drain(stack.copyWithAmount(moved), IFluidHandler.FluidAction.EXECUTE);
                    int received = b.fill(extracted, IFluidHandler.FluidAction.EXECUTE);
                    if (received != extracted.getAmount()) throw new IllegalStateException("Fluid handler violated its simulation result");
                    moved = received;
                }
            }
            case "item" -> {
                var a = level.getCapability(Capabilities.ItemHandler.BLOCK, source, from); var b = level.getCapability(Capabilities.ItemHandler.BLOCK, target, to);
                if (a == null || b == null) throw new IllegalArgumentException("Both faces need item capabilities");
                int slot = Defs.i(json, "slot", 0);
                if (slot < 0 || slot >= a.getSlots()) throw new IllegalArgumentException("Source slot is out of range");
                if (b.getSlots() > 256) throw new IllegalArgumentException("Target inventory exceeds the bounded transfer limit (256 slots)");
                ItemStack offered = a.extractItem(slot, Math.min(amount, 64), true);
                moved = offered.getCount() - ItemHandlerHelper.insertItemStacked(b, offered, true).getCount();
                if (!simulate && moved > 0) {
                    var extracted = a.extractItem(slot, moved, false); var remainder = ItemHandlerHelper.insertItemStacked(b, extracted, false);
                    if (!remainder.isEmpty()) { var returned = a.insertItem(slot, remainder, false); if (!returned.isEmpty()) net.minecraft.world.Containers.dropItemStack(level, source.getX(), source.getY(), source.getZ(), returned); }
                    moved = extracted.getCount() - remainder.getCount();
                }
            }
            default -> throw new IllegalArgumentException("Transfer kind must be item, fluid or energy");
        }
        JsonObject out = new JsonObject(); out.addProperty("ok", true); out.addProperty("moved", moved); out.addProperty("simulate", simulate); return out;
    }
}
