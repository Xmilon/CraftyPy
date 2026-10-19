package net.xmilon.craftpy.analysis;

import java.util.Map;
import java.util.Set;

/**
 * Which kind of id a string argument of the CraftPy API holds, from the call and the argument ({@code effect("...")} is a
 * mob effect, {@code shaped(result="...")} an item). The kinds are catalog registry ids ({@code item}, {@code mob_effect}...)
 * plus {@code class} for {@code java.cls("...")}. Returns null where a string is not an id.
 */
public final class IdKinds {
    /** The class names of the Java bridge are not a registry; the engine looks them up in the mods' class lists. */
    public static final String CLASS = "class";
    /** Event names of {@code @on("...")}; the same list the worker completes {@code on.} from. */
    public static final String EVENT = "event";
    public static final java.util.List<String> EVENTS = java.util.List.of("block_break", "block_place", "block_interact",
            "block_tick", "item_use", "item_hit", "item_mine", "item_hold_tick", "item_craft", "item_pickup", "player_join",
            "player_leave", "player_respawn", "player_death", "entity_death", "entity_hurt", "entity_tick", "machine_speed", "machine_state", "chat");

    private static final String[] ITEM = {"item"};
    private static final String[] ITEM_REPEATED = {"item", "item..."};

    /** Positional arguments by call name. A trailing {@code ...} repeats the last kind for every further argument. */
    private static final Map<String, String[]> POSITIONAL = Map.ofEntries(
            Map.entry("shaped", new String[]{"item"}),
            Map.entry("shapeless", ITEM_REPEATED),
            Map.entry("smelt", new String[]{"item", "item"}),
            Map.entry("blast", new String[]{"item", "item"}),
            Map.entry("smoke", new String[]{"item", "item"}),
            Map.entry("campfire", new String[]{"item", "item"}),
            Map.entry("stonecut", new String[]{"item", "item"}),
            Map.entry("give", ITEM),
            Map.entry("take", ITEM),
            Map.entry("count", ITEM),
            Map.entry("has", ITEM),
            Map.entry("drop", ITEM),
            Map.entry("effect", new String[]{"mob_effect"}),
            Map.entry("clear_effect", new String[]{"mob_effect"}),
            Map.entry("play_sound", new String[]{"sound_event"}),
            Map.entry("particle", new String[]{"particle_type"}),
            Map.entry("spawn", new String[]{"entity_type"}),
            Map.entry("recipe", new String[]{"recipe_type"}),
            Map.entry("attribute", new String[]{"attribute"}),
            Map.entry("Attributes", new String[]{"attribute"}),
            Map.entry("on", new String[]{EVENT}),
            Map.entry("cls", new String[]{CLASS}),
            Map.entry("new", new String[]{CLASS}));

    private static final Map<String, String> KEYWORDS = Map.ofEntries(
            Map.entry("block", "block"),
            Map.entry("item", "item"),
            Map.entry("entity", "entity_type"),
            Map.entry("drops", "item"),
            Map.entry("result", "item"),
            Map.entry("ingredient", "item"),
            Map.entry("effect", "mob_effect"),
            Map.entry("sound", "sound_event"),
            Map.entry("particle", "particle_type"),
            Map.entry("attribute", "attribute"),
            Map.entry("enchantment", "enchantment"));

    /** Calls whose single-letter keywords are the ingredients of a pattern ({@code shaped("x", "R", R="...")}). */
    private static final Set<String> PATTERN_CALLS = Set.of("shaped", "recipe_shaped");

    private IdKinds() {
    }

    public static String kindFor(StringContext.Call call) {
        if (call.callee().isEmpty()) {
            return null;
        }
        if (call.keyword() != null) {
            String keyword = call.keyword();
            if (keyword.equals("base") && call.callee().equals("NativeBlock")) return "block";
            if (keyword.equals("base") && call.callee().equals("NativeMob")) return "entity_type";
            if (keyword.equals("type")) {
                return call.callee().equals("recipe") ? "recipe_type"
                        : Set.of("entities", "spawn").contains(call.callee()) ? "entity_type" : null;
            }
            if (PATTERN_CALLS.contains(call.callee()) && keyword.length() == 1) {
                return "item";
            }
            return KEYWORDS.get(keyword);
        }
        String[] kinds = POSITIONAL.get(call.callee());
        if (kinds == null || call.argument() < 0) {
            return null;
        }
        String last = kinds[kinds.length - 1];
        String kind = call.argument() < kinds.length ? kinds[call.argument()] : last.endsWith("...") ? last : null;
        if (kind == null) {
            return null;
        }
        return kind.endsWith("...") ? kind.substring(0, kind.length() - 3) : kind;
    }
}
