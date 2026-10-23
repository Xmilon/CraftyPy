package net.xmilon.craftpy.content;

import com.google.gson.JsonObject;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.xmilon.craftpy.lifecycle.EventBridge;
import net.xmilon.craftpy.registry.KineticSlots;
import net.xmilon.craftpy.registry.SlotRegistry;

/** Native inventory, resource storage and processing shared by every rotation adapter. */
public final class MachineState {
    private final BlockEntity entity;
    private final MachineAccess host;
    public final KineticInventory inventory;
    public final StoredEnergy energy = new StoredEnergy();
    /** The tanks of FluidTank(..., tanks=n); {@link #fluid} is the first one, where a single tank always was. */
    public final Tanks tanks;
    public final FluidTank fluid;
    private CompoundTag retained = new CompoundTag();
    private Defs.BlockDef applied;
    private boolean configured;
    private boolean clientRotation;
    private boolean dirty;
    private long revision, reportedRevision;
    private int progress, duration;
    /** Values Python set for the window (bars and labels bound with value:key). */
    private final java.util.Map<String, com.google.gson.JsonPrimitive> values = new java.util.LinkedHashMap<>();
    private String recipe = "";
    private boolean running;
    private float reportedSpeed = Float.NaN;
    private String reportedState = "";
    private static int eventTick, events;
    private static Object eventServer;
    public static void resetNotifications() { eventServer = null; events = 0; eventTick = 0; }

    /** Machine block entities that ticked on a server; unloaded ones are dropped by the GC or by {@link #loaded}. */
    private static final java.util.Set<BlockEntity> TICKING = java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

    /** The machine block entities that are loaded on the server now (for backups). */
    public static java.util.List<BlockEntity> loaded() {
        synchronized (TICKING) {
            TICKING.removeIf(entity -> entity.isRemoved() || entity.getLevel() == null);
            return java.util.List.copyOf(TICKING);
        }
    }

    public MachineState(BlockEntity entity) {
        this.entity = entity; host = (MachineAccess) entity;
        inventory = new KineticInventory() { @Override protected void onContentsChanged(int slot) { changed(); } };
        tanks = new Tanks(stack -> {
            var def = definition();
            if (def == null || def.fluid == null) return false;
            var allowed = def.fluid.get("allowed");
            return allowed == null || allowed.isJsonNull() || allowed.getAsJsonArray().asList().stream()
                    .anyMatch(id -> net.xmilon.craftpy.registry.SlotRegistry.resolve(id.getAsString(), net.xmilon.craftpy.registry.SlotRegistry.Kind.FLUID)
                            .equals(BuiltInRegistries.FLUID.getKey(stack.getFluid()).toString()));
        }, this::changed);
        fluid = tanks.tank(0);
    }
    /** 0..100: how far the current recipe is (0 when the machine has no recipe running). */
    public int progressPercent() { return duration <= 0 ? 0 : Math.min(100, progress * 100 / duration); }
    public Defs.BlockDef definition() { return SlotRegistry.kineticDef(KineticSlots.index(entity.getBlockState().getBlock())); }
    public KineticDef rotation() { var def = definition(); return def == null ? null : def.kinetic; }
    public boolean processing() { var def = rotation(); return def != null && def.role().equals("processor"); }
    public boolean rotates() { return entity.getLevel() == null || entity.getLevel().isClientSide ? clientRotation : rotation() != null; }
    private void changed() { entity.setChanged(); dirty = true; revision++; }
    public void configure() {
        var def = definition();
        if (configured && def == applied) return;
        configured = true;
        applied = def;
        // Stored contents survive definition removal/reduction; extraction remains possible, insertion is capped.
        inventory.configure(def == null ? null : def.inventory);
        energy.configure(def == null ? null : def.energy);
        tanks.configure(def == null || def.fluid == null ? 0 : def.fluid.has("tanks") ? def.fluid.get("tanks").getAsInt() : 1,
                def == null || def.fluid == null ? 0 : def.fluid.get("capacity").getAsInt());
        if (entity.getLevel() != null) entity.getLevel().invalidateCapabilities(entity.getBlockPos());
        if (entity.getLevel() != null && !entity.getLevel().isClientSide) host.syncMachine();
    }
    public java.util.Map<String, com.google.gson.JsonPrimitive> values() { return new java.util.LinkedHashMap<>(values); }

    /** Python's world.machine_set: false (and nothing changed) when a key or value is not allowed. */
    public boolean setValues(JsonObject changes) {
        if (!net.xmilon.craftpy.menu.ScreenValues.merge(values, changes)) return false;
        changed();
        return true;
    }

    /** Python's world.machine_update: energy between 0 and the capacity, and at most what the tank holds. */
    public void adjust(int energyDelta, int fluidDelta) {
        adjust(energyDelta, fluidDelta, 0);
    }

    /** As above for one of the tanks. */
    public void adjust(int energyDelta, int fluidDelta, int tank) {
        configure();
        if (energyDelta != 0) energy.adjust(energyDelta);
        if (fluidDelta < 0) tanks.tank(tank).drain(-fluidDelta, net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
        host.syncMachine();
    }

    /**
     * The first item slot of a block with a Screen and a FluidTank takes a filled bucket or tank item: its fluid goes into the
     * tank (only what the tank accepts) and the emptied container comes out in the second slot.
     */
    /** The output slot can take {@code stack} on top of what it holds (an output slot refuses hands, but not the machine). */
    private boolean stowable(int slot, ItemStack stack) {
        ItemStack held = inventory.getStackInSlot(slot);
        return held.isEmpty() || ItemStack.isSameItemSameComponents(held, stack)
                && held.getCount() + stack.getCount() <= Math.min(inventory.getSlotLimit(slot), stack.getMaxStackSize());
    }

    /** Python's world.machine_slot: replaces what a slot holds ({@code group.n}); an empty stack clears it. */
    public String setSlot(String slotName, ItemStack stack) {
        configure();
        String[] parts = slotName.split("\\.", 2);
        int n;
        try { n = parts.length > 1 ? Integer.parseInt(parts[1]) : 0; } catch (NumberFormatException bad) { return "unknown slot " + slotName; }
        int slot = inventory.slotOf(parts[0], n);
        if (slot < 0) return "unknown slot " + slotName;
        if (stack.getCount() > inventory.getSlotLimit(slot)) stack.setCount(inventory.getSlotLimit(slot));
        inventory.setStackInSlot(slot, stack);
        host.syncMachine();
        return "";
    }

    private void emptyContainerSlot() {
        var def = definition();
        if (def == null || def.screen == null || def.fluid == null) return;
        int inSlot = inventory.inputSlot(), outSlot = inventory.outputSlot();
        if (inSlot < 0 || outSlot < 0) return;
        ItemStack in = inventory.getStackInSlot(inSlot);
        if (in.isEmpty()) return;
        var simulated = net.neoforged.neoforge.fluids.FluidUtil.tryEmptyContainer(in.copyWithCount(1), tanks, Integer.MAX_VALUE, null, false);
        if (!simulated.isSuccess() || !stowable(outSlot, simulated.getResult())) return;
        var done = net.neoforged.neoforge.fluids.FluidUtil.tryEmptyContainer(in.copyWithCount(1), tanks, Integer.MAX_VALUE, null, true);
        if (!done.isSuccess()) return;
        ItemStack out = inventory.getStackInSlot(outSlot).copy();
        if (out.isEmpty()) out = done.getResult().copy(); else out.grow(done.getResult().getCount());
        inventory.setStackInSlot(outSlot, out);
        inventory.extractItem(inSlot, 1, false);
    }

    public void tick() {
        configure();
        var level = entity.getLevel();
        if (level == null || level.isClientSide) return;
        TICKING.add(entity);
        if (ContainerSlots.any(inventory.spec())) {
            if (ContainerSlots.tick(inventory, tanks, energy)) host.syncMachine();
        } else {
            emptyContainerSlot();
        }
        boolean before = running; running = false;
        if (processing()) {
            var match = level.getRecipeManager().getRecipeFor(KineticRecipe.TYPE.get(),
                    new SingleRecipeInput(inventory.inputSlot() < 0 ? ItemStack.EMPTY : inventory.getStackInSlot(inventory.inputSlot())), level);
            String next = match.map(r -> r.id().toString()).orElse("");
            duration = match.map(r -> r.value().duration()).orElse(0);
            if (!next.equals(recipe)) { recipe = next; progress = 0; changed(); }
            if (match.isPresent()) {
                var value = match.get().value();
                if (host.machineSpeed() != 0 && host.rotationState().equals("ready")
                        && Math.abs(host.machineSpeed()) >= value.minimumSpeed() && inventory.canFinish(value.output())) {
                    running = true; progress++; changed();
                    if (progress >= value.duration() && inventory.finish(value.output())) { progress = 0; host.syncMachine(); }
                }
            }
        }
        if (before != running) { changed(); host.syncMachine(); }
        if (Math.floorMod(entity.getBlockPos().hashCode(), 20) == Math.floorMod(level.getGameTime(), 20)) {
            if (dirty) { dirty = false; host.syncMachine(); }
            publish();
        }
    }
    private String state() { return definition() == null ? "inactive" : !host.rotationState().equals("ready") ? host.rotationState() : running ? "processing" : "idle"; }
    private void publish() {
        var level = entity.getLevel(); var server = level.getServer();
        float speed = host.machineSpeed(); String state = state();
        boolean speedChange = speed != reportedSpeed, stateChange = !state.equals(reportedState) || revision != reportedRevision;
        if (!speedChange && !stateChange) return;
        if (eventServer != server || eventTick != server.getTickCount()) { eventServer = server; eventTick = server.getTickCount(); events = 0; }
        if (events >= 64) return;
        JsonObject context = new JsonObject(); context.add("machine", snapshot());
        context.add("block", EventBridge.wrapBlock(entity.getBlockState()));
        context.addProperty("id", EventBridge.projectId(SlotRegistry.holderFor(SlotRegistry.Kind.KINETIC, KineticSlots.index(entity.getBlockState().getBlock()))));
        EventBridge.addPosition(context, entity.getBlockPos(), level); context.addProperty("tick", level.getGameTime());
        for (String event : java.util.List.of("machine_speed", "machine_state"))
            if ((event.equals("machine_speed") ? speedChange : stateChange) && SlotRegistry.handlesEvent(event) && events++ < 64)
                EventBridge.fire(event, level, context.deepCopy());
        reportedSpeed = speed; reportedState = state; reportedRevision = revision;
    }
    private static JsonObject stackJson(ItemStack stack) {
        JsonObject item = new JsonObject();
        item.addProperty("item", EventBridge.itemId(stack)); // project:id for CraftPy content, like events name it
        item.addProperty("count", stack.getCount());
        return item;
    }

    /** What hoppers, pipes and other mods get of the slots from {@code side} (null: any side); null when they get none. */
    public net.neoforged.neoforge.items.IItemHandler automation(net.minecraft.core.Direction side) {
        configure();
        if (inventory.spec() != null) return new AutomationView(inventory, side);
        return processing() ? inventory : null;
    }

    public JsonObject snapshot() {
        configure();
        JsonObject out = new JsonObject(); out.addProperty("active", definition() != null);
        out.addProperty("speed", host.machineSpeed()); out.addProperty("theoretical_speed", host.theoreticalSpeed());
        out.addProperty("overstressed", host.rotationState().equals("overstressed")); out.addProperty("state", state());
        out.addProperty("processing", running); out.addProperty("progress", progress); out.addProperty("recipe", recipe);
        out.addProperty("backend", MachineBackend.selected().id());
        out.add("input", stackJson(inventory.inputSlot() < 0 ? ItemStack.EMPTY : inventory.getStackInSlot(inventory.inputSlot())));
        out.add("output", stackJson(inventory.outputSlot() < 0 ? ItemStack.EMPTY : inventory.getStackInSlot(inventory.outputSlot())));
        // every group with all its slots, in order: inventory["fuel"][0]["item"]
        JsonObject groups = new JsonObject();
        for (int i = 0; i < inventory.getSlots(); i++) {
            String name = inventory.groupOf(i).isEmpty() ? "overflow" : inventory.groupOf(i);
            if (!groups.has(name)) groups.add(name, new com.google.gson.JsonArray());
            groups.getAsJsonArray(name).add(stackJson(inventory.getStackInSlot(i)));
        }
        out.add("inventory", groups);
        out.add("values", net.xmilon.craftpy.menu.ScreenValues.toJson(values));
        out.addProperty("energy", energy.getEnergyStored()); out.addProperty("energy_capacity", energy.getMaxEnergyStored());
        out.addProperty("fluid", net.xmilon.craftpy.lifecycle.EventBridge.fluidId(fluid.getFluid().getFluid()));
        out.addProperty("fluid_amount", fluid.getFluidAmount()); out.addProperty("fluid_capacity", fluid.getCapacity());
        // every tank: tanks[1]["fluid"], ["amount"], ["capacity"]
        com.google.gson.JsonArray all = new com.google.gson.JsonArray();
        for (int i = 0; i < tanks.count(); i++) {
            JsonObject tank = new JsonObject();
            tank.addProperty("fluid", net.xmilon.craftpy.lifecycle.EventBridge.fluidId(tanks.tank(i).getFluid().getFluid()));
            tank.addProperty("amount", tanks.tank(i).getFluidAmount());
            tank.addProperty("capacity", tanks.tank(i).getCapacity());
            all.add(tank);
        }
        out.add("tanks", all);
        return out;
    }
    public void save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.merge(retained); tag.put("CraftPyInventory", inventory.serializeNBT(registries));
        tag.putString("CraftPyValues", net.xmilon.craftpy.menu.ScreenValues.toJson(values).toString());
        tag.putInt("CraftPyProgress", progress); tag.putString("CraftPyRecipe", recipe); tag.putBoolean("CraftPyRunning", running);
        tag.putInt("CraftPyEnergy", energy.getEnergyStored()); tanks.save(tag, "CraftPyFluid", "CraftPyFluids", registries);
        tag.putBoolean("CraftPyHasRotation", rotation() != null);
    }
    public void load(CompoundTag tag, HolderLookup.Provider registries) {
        retained = tag.copy(); for (String key : java.util.List.copyOf(retained.getAllKeys())) if (!key.startsWith("CraftPy")) retained.remove(key);
        inventory.deserializeNBT(registries, tag.getCompound("CraftPyInventory"));
        values.clear();
        String savedValues = tag.getString("CraftPyValues");
        if (!savedValues.isEmpty() && savedValues.length() < 20_000) {
            try { net.xmilon.craftpy.menu.ScreenValues.merge(values, com.google.gson.JsonParser.parseString(savedValues).getAsJsonObject()); }
            catch (RuntimeException damaged) { values.clear(); }
        }
        progress = Math.clamp(tag.getInt("CraftPyProgress"), 0, 72000); recipe = tag.getString("CraftPyRecipe"); running = false;
        clientRotation = tag.getBoolean("CraftPyHasRotation");
        energy.stored(Math.max(0, tag.getInt("CraftPyEnergy"))); tanks.load(tag, "CraftPyFluid", "CraftPyFluids", registries);
        configured = false; applied = null; configure();
    }
    public static JsonObject validateEnergy(JsonObject json) {
        positive(json, "capacity", 1, 1_000_000_000); positive(json, "max_receive", 0, 1_000_000_000); positive(json, "max_extract", 0, 1_000_000_000); return json.deepCopy();
    }
    public static JsonObject validateFluid(JsonObject json) {
        positive(json, "capacity", 1, 1_000_000);
        if (json.has("tanks")) positive(json, "tanks", 1, Tanks.MAX);
        if (json.has("allowed") && !json.get("allowed").isJsonNull()) {
            var values = json.getAsJsonArray("allowed");
            if (values.size() > 64) throw new IllegalArgumentException("FluidTank allows at most 64 fluid IDs");
            for (var value : values) if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || net.minecraft.resources.ResourceLocation.tryParse(value.getAsString()) == null)
                throw new IllegalArgumentException("FluidTank needs fluid resource IDs");
        }
        return json.deepCopy();
    }
    private static int positive(JsonObject json, String key, int min, int max) {
        var value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Machine " + key + " must be an integer");
        double number = value.getAsDouble();
        if (!Double.isFinite(number) || number != Math.rint(number) || number < min || number > max) throw new IllegalArgumentException("Machine " + key + " outside " + min + ".." + max);
        return (int) number;
    }
    public final class StoredEnergy extends EnergyStorage {
        private int insertionCapacity;
        StoredEnergy() { super(0); }
        void stored(int amount) { energy = amount; capacity = Math.max(capacity, amount); }
        void configure(JsonObject def) {
            insertionCapacity = def == null ? 0 : def.get("capacity").getAsInt(); capacity = Math.max(energy, insertionCapacity);
            maxReceive = def == null ? 0 : def.get("max_receive").getAsInt(); maxExtract = def == null ? Integer.MAX_VALUE : def.get("max_extract").getAsInt();
        }
        /** Python's change: bypasses the per-call limits, never leaves 0..capacity. */
        void adjust(int delta) {
            int next = (int) Math.max(0, Math.min((long) insertionCapacity, (long) energy + delta));
            if (next != energy) { energy = next; capacity = Math.max(energy, insertionCapacity); changed(); }
        }
        @Override public int receiveEnergy(int amount, boolean simulate) { int n = super.receiveEnergy(Math.min(Math.max(0, amount), Math.max(0, insertionCapacity - energy)), simulate); if (n > 0 && !simulate) changed(); return n; }
        @Override public int extractEnergy(int amount, boolean simulate) { int n = super.extractEnergy(Math.max(0, amount), simulate); capacity = Math.max(energy, insertionCapacity); if (n > 0 && !simulate) changed(); return n; }
    }
}
