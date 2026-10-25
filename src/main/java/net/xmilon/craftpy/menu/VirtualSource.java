package net.xmilon.craftpy.menu;

import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.xmilon.craftpy.content.ContainerSlots;
import net.xmilon.craftpy.content.Defs;
import net.xmilon.craftpy.content.KineticInventory;
import net.xmilon.craftpy.content.Tanks;
import net.xmilon.craftpy.lifecycle.EventBridge;

/**
 * A window opened from Python ({@code ui.open_screen}) that belongs to an entity, a player or an item in a player's hand: item
 * slots (a villager's wares, a player's backpack, a backpack item's pockets), up to {@link Tanks#MAX} liquid tanks, an energy
 * store and free values for bars and labels. Everything is kept under {@code craftpy_screens.<window>}: in the entity's
 * persistent data, or in the held item's custom data, so the item carries its contents wherever it goes (and an entity that is
 * picked up as an item, and placed again, keeps them). While a held item's window is open that item cannot be moved.
 */
public final class VirtualSource implements MenuSource {
    public static final String ROOT = "craftpy_screens";

    /** Where the window's data lives. */
    private interface Holder {
        CompoundTag root();

        void store(CompoundTag root);

        boolean valid(Player player);

        void describe(JsonObject context);

        HolderLookup.Provider registries();
    }

    private final Holder holder;
    private final String key;
    private final String owner;
    private final KineticInventory inventory;
    private final Tanks tanks;
    private final Power energy;
    private final Map<String, JsonPrimitive> values = new LinkedHashMap<>();
    /** The player inventory slot of the held item whose window this is, or -1. */
    private final int lockedSlot;
    private boolean loading = true;

    private VirtualSource(Holder holder, String key, String owner, Defs.InventorySpec spec, int energyCapacity, int tankCount,
                          int tankCapacity, int lockedSlot) {
        this.holder = holder;
        this.key = key;
        this.owner = owner;
        this.lockedSlot = lockedSlot;
        this.inventory = new KineticInventory() {
            @Override
            protected void onContentsChanged(int slot) {
                save();
            }
        };
        this.tanks = new Tanks(stack -> true, this::save);
        this.energy = new Power(Math.max(0, energyCapacity));
        HolderLookup.Provider registries = holder.registries();
        CompoundTag stored = holder.root().getCompound(key);
        if (stored.contains("Inventory")) {
            inventory.deserializeNBT(registries, stored.getCompound("Inventory"));
        }
        inventory.configure(spec);
        tanks.configure(tankCount, tankCapacity);
        tanks.load(stored, "Tank", "Tanks", registries);
        int saved = Math.max(0, stored.getInt("Energy"));
        // energy above a smaller capacity is kept, like items of a smaller inventory: it can only be taken out
        energy.set(saved, Math.max(saved, energyCapacity));
        String json = stored.getString("Values");
        if (!json.isEmpty() && json.length() < 20_000) {
            try {
                ScreenValues.merge(values, JsonParser.parseString(json).getAsJsonObject());
            } catch (RuntimeException damaged) {
                values.clear();
            }
        }
        loading = false;
    }

    /** The energy store: NeoForge's, saved after every change. */
    private final class Power extends EnergyStorage {
        Power(int capacity) {
            super(capacity);
        }

        void set(int amount, int capacity) {
            this.capacity = capacity;
            this.energy = amount;
        }

        @Override
        public int receiveEnergy(int amount, boolean simulate) {
            int moved = super.receiveEnergy(amount, simulate);
            if (moved > 0 && !simulate) save();
            return moved;
        }

        @Override
        public int extractEnergy(int amount, boolean simulate) {
            int moved = super.extractEnergy(amount, simulate);
            if (moved > 0 && !simulate) save();
            return moved;
        }
    }

    private static String clean(String key) {
        String safe = key.replaceAll("[^A-Za-z0-9_-]", "_");
        return safe.substring(0, Math.min(64, safe.length()));
    }

    /** The window state of {@code entity} (a mob, a player) for the window {@code key}. */
    public static VirtualSource of(Entity entity, String key, String owner, Defs.InventorySpec spec, int energy, int tanks, int tankCapacity) {
        if (entity instanceof net.xmilon.craftpy.entity.PropEntity prop) {
            // a prop keeps its windows in its item, so they are the same in the hand and on the floor
            ItemStack stack = prop.item();
            return new VirtualSource(new Holder() {
                @Override public CompoundTag root() { return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompound(ROOT); }
                @Override public void store(CompoundTag root) { CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.put(ROOT, root)); }
                @Override public boolean valid(Player player) { return prop.isAlive() && prop.item() == stack && prop.distanceToSqr(player) <= 64; }
                @Override public void describe(JsonObject context) {
                    context.add("entity", EventBridge.wrapEntity(prop));
                    context.add("item", EventBridge.wrapItem(stack));
                }
                @Override public HolderLookup.Provider registries() { return prop.level().registryAccess(); }
            }, clean(key), owner, spec, energy, tanks, tankCapacity, -1);
        }
        return new VirtualSource(new Holder() {
            @Override public CompoundTag root() { return entity.getPersistentData().getCompound(ROOT); }
            @Override public void store(CompoundTag root) { entity.getPersistentData().put(ROOT, root); }
            @Override public boolean valid(Player player) { return entity.isAlive() && (entity == player || entity.distanceToSqr(player) <= 64); }
            @Override public void describe(JsonObject context) { context.add("entity", EventBridge.wrapEntity(entity)); }
            @Override public HolderLookup.Provider registries() { return entity.level().registryAccess(); }
        }, clean(key), owner, spec, energy, tanks, tankCapacity, -1);
    }

    /** The window state kept in the item {@code player} holds in {@code hand}; null when the hand is empty. */
    public static VirtualSource ofHeld(Player player, InteractionHand hand, String key, String owner, Defs.InventorySpec spec,
                                       int energy, int tanks, int tankCapacity) {
        ItemStack stack = player.getItemInHand(hand);
        if (stack.isEmpty()) return null;
        int slot = hand == InteractionHand.MAIN_HAND ? player.getInventory().selected : 40;
        return new VirtualSource(new Holder() {
            @Override public CompoundTag root() { return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompound(ROOT); }
            @Override public void store(CompoundTag root) { CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.put(ROOT, root)); }
            // the very same stack must still be in that hand: dropped, thrown or moved closes the window
            @Override public boolean valid(Player viewer) { return viewer == player && player.getItemInHand(hand) == stack && !stack.isEmpty(); }
            @Override public void describe(JsonObject context) {
                context.add("item", EventBridge.wrapItem(stack));
                context.addProperty("hand", hand == InteractionHand.MAIN_HAND ? "main" : "off");
            }
            @Override public HolderLookup.Provider registries() { return player.level().registryAccess(); }
        }, clean(key), owner, spec, energy, tanks, tankCapacity, slot);
    }

    /** Replaces or adds values; false when one of them is not allowed (nothing changes then). */
    public boolean setValues(JsonObject changes) {
        boolean ok = ScreenValues.merge(values, changes);
        if (ok) {
            save();
        }
        return ok;
    }

    private void save() {
        if (loading) return;
        HolderLookup.Provider registries = holder.registries();
        CompoundTag root = holder.root();
        CompoundTag mine = new CompoundTag();
        mine.put("Inventory", inventory.serializeNBT(registries));
        mine.putString("Values", ScreenValues.toJson(values).toString());
        mine.putInt("Energy", energy.getEnergyStored());
        tanks.save(mine, "Tank", "Tanks", registries);
        root.put(key, mine);
        holder.store(root);
    }

    /** Adds energy (negative takes it) and removes {@code -fluid} mB from {@code tank}, within what it holds. */
    public void adjust(int energyDelta, int fluid, int tank) {
        if (energyDelta > 0) energy.receiveEnergy(energyDelta, false);
        if (energyDelta < 0) energy.extractEnergy(-energyDelta, false);
        if (fluid < 0 && tank < tanks.count()) tanks.tank(tank).drain(-fluid, net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
        save();
    }

    /** What Python sees of the window: its energy, tanks (liquids by project id) and values. */
    public JsonObject snapshot() {
        JsonObject out = new JsonObject();
        out.addProperty("energy", energy.getEnergyStored());
        out.addProperty("energy_capacity", energy.getMaxEnergyStored());
        com.google.gson.JsonArray all = new com.google.gson.JsonArray();
        for (int i = 0; i < tanks.count(); i++) {
            JsonObject tank = new JsonObject();
            tank.addProperty("fluid", EventBridge.fluidId(tanks.tank(i).getFluid().getFluid()));
            tank.addProperty("amount", tanks.tank(i).getFluidAmount());
            tank.addProperty("capacity", tanks.tank(i).getCapacity());
            all.add(tank);
        }
        out.add("tanks", all);
        out.add("values", ScreenValues.toJson(values));
        return out;
    }

    /** The slots of one group as Python sees them: item (project id), count, name, and food values when it is food. */
    public com.google.gson.JsonArray slots(String group) {
        com.google.gson.JsonArray out = new com.google.gson.JsonArray();
        for (int i = 0; i < inventory.getSlots(); i++) {
            if (!group.equals(inventory.groupOf(i))) continue;
            ItemStack stack = inventory.getStackInSlot(i);
            JsonObject entry = new JsonObject();
            entry.addProperty("item", EventBridge.itemId(stack));
            entry.addProperty("count", stack.getCount());
            entry.addProperty("name", stack.isEmpty() ? "" : stack.getHoverName().getString());
            var food = stack.getFoodProperties(null);
            if (food != null) {
                entry.addProperty("food", food.nutrition());
                entry.addProperty("saturation", food.saturation());
            }
            out.add(entry);
        }
        return out;
    }

    /** Removes up to {@code count} items from slot {@code index} of {@code group}; how many came out. */
    public int take(String group, int index, int count) {
        int slot = inventory.slotOf(group, index);
        if (slot < 0 || count <= 0) return 0;
        return inventory.extractItem(slot, count, false).getCount();
    }

    /** Merges equal stacks of a group and puts them in order (by item id, then name, then the fullest first). */
    public void sort(String group) {
        java.util.List<Integer> slots = new java.util.ArrayList<>();
        java.util.List<ItemStack> merged = new java.util.ArrayList<>();
        for (int i = 0; i < inventory.getSlots(); i++) {
            if (!group.equals(inventory.groupOf(i))) continue;
            slots.add(i);
            ItemStack rest = inventory.getStackInSlot(i).copy();
            for (ItemStack into : merged) {
                if (rest.isEmpty()) break;
                if (ItemStack.isSameItemSameComponents(into, rest) && into.getCount() < into.getMaxStackSize()) {
                    int moved = Math.min(rest.getCount(), into.getMaxStackSize() - into.getCount());
                    into.grow(moved);
                    rest.shrink(moved);
                }
            }
            if (!rest.isEmpty()) merged.add(rest);
        }
        merged.sort(java.util.Comparator.<ItemStack, String>comparing(stack -> EventBridge.itemId(stack))
                .thenComparing(stack -> stack.getHoverName().getString()).thenComparing(stack -> -stack.getCount()));
        for (int n = 0; n < slots.size(); n++) {
            inventory.setStackInSlot(slots.get(n), n < merged.size() ? merged.get(n) : ItemStack.EMPTY);
        }
    }

    /** The player inventory slot that holds this window's item (it may not be moved while open), or -1. */
    public int lockedSlot() {
        return lockedSlot;
    }

    @Override public KineticInventory inventory() { return inventory; }
    @Override public int energy() { return energy.getEnergyStored(); }
    @Override public int energyCapacity() { return energy.getMaxEnergyStored(); }
    @Override public int tankCount() { return tanks.count(); }
    @Override public int fluidAmount(int tank) { return tanks.tank(tank).getFluidAmount(); }
    @Override public int fluidCapacity(int tank) { return tanks.tank(tank).getCapacity(); }
    @Override public int fluidId(int tank) { return BuiltInRegistries.FLUID.getId(tanks.tank(tank).getFluid().getFluid()); }
    @Override public int progressPercent() { return 0; }
    @Override public Map<String, JsonPrimitive> values() { return new LinkedHashMap<>(values); }
    @Override public String owner() { return owner; }

    @Override
    public void describe(JsonObject context) {
        holder.describe(context);
        // the layout's name, as @on.screen_button(window="pack") names it (the key starts with the owner)
        String prefix = clean(owner) + "_";
        context.addProperty("window", key.startsWith(prefix) ? key.substring(prefix.length()) : key);
    }

    @Override
    public boolean stillValid(Player player) {
        return holder.valid(player);
    }

    @Override
    public void tick() {
        if (ContainerSlots.any(inventory.spec())) {
            ContainerSlots.tick(inventory, tanks.count() > 0 ? tanks : null, energy.getMaxEnergyStored() > 0 ? energy : null);
        }
    }

    @Override
    public void closed() {
        save();
    }
}
