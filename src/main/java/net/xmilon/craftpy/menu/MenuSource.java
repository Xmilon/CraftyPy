package net.xmilon.craftpy.menu;

import java.util.Map;

import com.google.gson.JsonPrimitive;

import net.minecraft.world.entity.player.Player;
import net.xmilon.craftpy.content.KineticInventory;

/**
 * What a machine window shows and edits: the item slots, the numbers behind the gauges, and the free values Python sets
 * (bars and labels bound with {@code value:key}). A machine block is one source; a window opened from Python for an entity or a
 * player ({@link VirtualSource}) is another, so the same layouts work for both.
 */
public interface MenuSource {
    KineticInventory inventory();

    int energy();

    int energyCapacity();

    int fluidAmount();

    int fluidCapacity();

    /** Registry id of the fluid in the tank (0 = empty). */
    int fluidId();

    int progressPercent();

    /** A copy of the values Python set: numbers and short texts. */
    Map<String, JsonPrimitive> values();

    boolean stillValid(Player player);

    /** The window was closed (or the player left): save what has to be kept. */
    default void closed() {
    }
}
