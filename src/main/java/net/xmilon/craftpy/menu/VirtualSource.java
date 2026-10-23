package net.xmilon.craftpy.menu;

import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.xmilon.craftpy.content.Defs;
import net.xmilon.craftpy.content.KineticInventory;

/**
 * A window opened from Python for an entity or a player ({@code ui.open_screen}): item slots that belong to that entity (a
 * villager's wares, a pack animal's bags, a player's own backpack) and free values for bars and labels. The items are kept in the
 * entity's persistent data under the layout's name, so they stay with it when the world is saved. Energy and liquid gauges show
 * nothing here.
 */
public final class VirtualSource implements MenuSource {
    private static final String ROOT = "craftpy_screens";

    private final Entity holder;
    private final String key;
    private final HolderLookup.Provider registries;
    private final KineticInventory inventory;
    private final Map<String, JsonPrimitive> values = new LinkedHashMap<>();

    private VirtualSource(Entity holder, String key, Defs.InventorySpec spec) {
        this.holder = holder;
        this.key = key;
        this.registries = holder.level().registryAccess();
        this.inventory = new KineticInventory() {
            @Override
            protected void onContentsChanged(int slot) {
                save();
            }
        };
        CompoundTag stored = holder.getPersistentData().getCompound(ROOT).getCompound(key);
        if (stored.contains("Inventory")) {
            inventory.deserializeNBT(registries, stored.getCompound("Inventory"));
        }
        inventory.configure(spec);
        String json = stored.getString("Values");
        if (!json.isEmpty() && json.length() < 20_000) {
            try {
                ScreenValues.merge(values, JsonParser.parseString(json).getAsJsonObject());
            } catch (RuntimeException damaged) {
                values.clear();
            }
        }
    }

    /** The window state of {@code holder} for the layout {@code key} (letters, digits, _ and -; at most 64). */
    public static VirtualSource of(Entity holder, String key, Defs.InventorySpec spec) {
        return new VirtualSource(holder, key.replaceAll("[^A-Za-z0-9_-]", "_").substring(0, Math.min(64, key.length())), spec);
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
        CompoundTag root = holder.getPersistentData().getCompound(ROOT);
        CompoundTag mine = new CompoundTag();
        mine.put("Inventory", inventory.serializeNBT(registries));
        mine.putString("Values", ScreenValues.toJson(values).toString());
        root.put(key, mine);
        holder.getPersistentData().put(ROOT, root);
    }

    @Override public KineticInventory inventory() { return inventory; }
    @Override public int energy() { return 0; }
    @Override public int energyCapacity() { return 0; }
    @Override public int fluidAmount() { return 0; }
    @Override public int fluidCapacity() { return 0; }
    @Override public int fluidId() { return 0; }
    @Override public int progressPercent() { return 0; }
    @Override public Map<String, JsonPrimitive> values() { return new LinkedHashMap<>(values); }

    @Override
    public boolean stillValid(Player player) {
        return holder.isAlive() && (holder == player || holder.distanceToSqr(player) <= 64);
    }

    @Override
    public void closed() {
        save();
    }
}
