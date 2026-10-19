package net.xmilon.craftpy.content;

import com.google.gson.JsonObject;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
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
    public final FluidTank fluid;
    private CompoundTag retained = new CompoundTag();
    private Defs.BlockDef applied;
    private boolean configured;
    private boolean clientRotation;
    private boolean dirty;
    private long revision, reportedRevision;
    private int progress;
    private String recipe = "";
    private boolean running;
    private float reportedSpeed = Float.NaN;
    private String reportedState = "";
    private static int eventTick, events;
    private static Object eventServer;
    public static void resetNotifications() { eventServer = null; events = 0; eventTick = 0; }

    public MachineState(BlockEntity entity) {
        this.entity = entity; host = (MachineAccess) entity;
        inventory = new KineticInventory() { @Override protected void onContentsChanged(int slot) { changed(); } };
        fluid = new FluidTank(0, stack -> {
            var def = definition();
            if (def == null || def.fluid == null) return false;
            var allowed = def.fluid.get("allowed");
            return allowed == null || allowed.isJsonNull() || allowed.getAsJsonArray().asList().stream()
                    .anyMatch(id -> id.getAsString().equals(BuiltInRegistries.FLUID.getKey(stack.getFluid()).toString()));
        }) {
            @Override protected void onContentsChanged() {
                var def = definition();
                setCapacity(Math.max(getFluidAmount(), def == null || def.fluid == null ? 0 : def.fluid.get("capacity").getAsInt()));
                changed();
            }
            @Override public int fill(net.neoforged.neoforge.fluids.FluidStack resource, net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction action) {
                var def = definition(); int limit = def == null || def.fluid == null ? 0 : def.fluid.get("capacity").getAsInt();
                return super.fill(resource.copyWithAmount(Math.min(resource.getAmount(), Math.max(0, limit - getFluidAmount()))), action);
            }
        };
    }
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
        energy.configure(def == null ? null : def.energy);
        fluid.setCapacity(Math.max(fluid.getFluidAmount(), def == null || def.fluid == null ? 0 : def.fluid.get("capacity").getAsInt()));
        if (entity.getLevel() != null) entity.getLevel().invalidateCapabilities(entity.getBlockPos());
        if (entity.getLevel() != null && !entity.getLevel().isClientSide) host.syncMachine();
    }
    public void tick() {
        configure();
        var level = entity.getLevel();
        if (level == null || level.isClientSide) return;
        boolean before = running; running = false;
        if (processing()) {
            var match = level.getRecipeManager().getRecipeFor(KineticRecipe.TYPE.get(), new SingleRecipeInput(inventory.getStackInSlot(0)), level);
            String next = match.map(r -> r.id().toString()).orElse("");
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
    public JsonObject snapshot() {
        configure();
        JsonObject out = new JsonObject(); out.addProperty("active", definition() != null);
        out.addProperty("speed", host.machineSpeed()); out.addProperty("theoretical_speed", host.theoreticalSpeed());
        out.addProperty("overstressed", host.rotationState().equals("overstressed")); out.addProperty("state", state());
        out.addProperty("processing", running); out.addProperty("progress", progress); out.addProperty("recipe", recipe);
        out.addProperty("backend", MachineBackend.selected().id());
        for (int i = 0; i < 2; i++) {
            var stack = inventory.getStackInSlot(i); JsonObject item = new JsonObject();
            item.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()); item.addProperty("count", stack.getCount());
            out.add(i == 0 ? "input" : "output", item);
        }
        out.addProperty("energy", energy.getEnergyStored()); out.addProperty("energy_capacity", energy.getMaxEnergyStored());
        out.addProperty("fluid", BuiltInRegistries.FLUID.getKey(fluid.getFluid().getFluid()).toString());
        out.addProperty("fluid_amount", fluid.getFluidAmount()); out.addProperty("fluid_capacity", fluid.getCapacity());
        return out;
    }
    public void save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.merge(retained); tag.put("CraftPyInventory", inventory.serializeNBT(registries));
        tag.putInt("CraftPyProgress", progress); tag.putString("CraftPyRecipe", recipe); tag.putBoolean("CraftPyRunning", running);
        tag.putInt("CraftPyEnergy", energy.getEnergyStored()); tag.put("CraftPyFluid", fluid.writeToNBT(registries, new CompoundTag()));
        tag.putBoolean("CraftPyHasRotation", rotation() != null);
    }
    public void load(CompoundTag tag, HolderLookup.Provider registries) {
        retained = tag.copy(); for (String key : java.util.List.copyOf(retained.getAllKeys())) if (!key.startsWith("CraftPy")) retained.remove(key);
        inventory.deserializeNBT(registries, tag.getCompound("CraftPyInventory"));
        progress = Math.clamp(tag.getInt("CraftPyProgress"), 0, 72000); recipe = tag.getString("CraftPyRecipe"); running = false;
        clientRotation = tag.getBoolean("CraftPyHasRotation");
        energy.stored(Math.max(0, tag.getInt("CraftPyEnergy"))); fluid.readFromNBT(registries, tag.getCompound("CraftPyFluid"));
        configured = false; applied = null; configure();
    }
    public static JsonObject validateEnergy(JsonObject json) {
        positive(json, "capacity", 1, 1_000_000_000); positive(json, "max_receive", 0, 1_000_000_000); positive(json, "max_extract", 0, 1_000_000_000); return json.deepCopy();
    }
    public static JsonObject validateFluid(JsonObject json) {
        positive(json, "capacity", 1, 1_000_000);
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
        @Override public int receiveEnergy(int amount, boolean simulate) { int n = super.receiveEnergy(Math.min(Math.max(0, amount), Math.max(0, insertionCapacity - energy)), simulate); if (n > 0 && !simulate) changed(); return n; }
        @Override public int extractEnergy(int amount, boolean simulate) { int n = super.extractEnergy(Math.max(0, amount), simulate); capacity = Math.max(energy, insertionCapacity); if (n > 0 && !simulate) changed(); return n; }
    }
}
