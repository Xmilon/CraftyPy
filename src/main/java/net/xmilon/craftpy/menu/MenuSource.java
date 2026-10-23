package net.xmilon.craftpy.menu;

import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.minecraft.world.entity.player.Player;
import net.xmilon.craftpy.content.KineticInventory;

/**
 * What a machine window shows and edits: the item slots, the numbers behind the gauges, and the free values Python sets
 * (bars and labels bound with {@code value:key}). A machine block is one source; a window opened from Python for an entity, a
 * player or a held item ({@link VirtualSource}) is another, so the same layouts work for all of them.
 */
public interface MenuSource {
    KineticInventory inventory();

    int energy();

    int energyCapacity();

    /** How many tanks there are (0 when none). */
    int tankCount();

    int fluidAmount(int tank);

    int fluidCapacity(int tank);

    /** Registry id of the fluid in a tank (0 = empty). */
    int fluidId(int tank);

    int progressPercent();

    /** A copy of the values Python set: numbers and short texts. */
    Map<String, JsonPrimitive> values();

    boolean stillValid(Player player);

    /** The project or module ({@code module:<id>}) whose Python hears the window's buttons, or "" for none. */
    String owner();

    /** Where the window belongs, for Python: the block's position and id, or the entity and the window's name. */
    void describe(JsonObject context);

    /** Once a tick while the window is open (server): container slots move liquid and energy. */
    default void tick() {
    }

    /** The window was closed (or the player left): save what has to be kept. */
    default void closed() {
    }
}
