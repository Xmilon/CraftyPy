package net.xmilon.craftpy.client.screen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Brief signatures and one-line descriptions for the CraftPy Python API and the
 * declaration fields. Pure data - shown in the code completion popup so every
 * suggestion explains what it does.
 */
public final class ApiDocs {

    /** One documented entry: what it looks like and what it does. */
    public record Doc(String signature, String description) {
    }

    /** One declaration field: its name and what it controls. */
    public record Field(String name, String description) {
    }

    private static final Map<String, Doc> DOCS = new LinkedHashMap<>();
    private static final Map<String, List<Field>> FIELDS = new LinkedHashMap<>();

    static {
        doc("block", "@block(\"id\", **fields)", "Declare a block. Tab inserts the full field template.");
        doc("item", "@item(\"id\", **fields)", "Declare an item. Tab inserts the full field template.");
        doc("entity", "@entity(\"id\", **fields)", "Declare a reskinned mob (passive/hostile base).");
        doc("effect", "@effect(\"id\", **fields)", "Declare a mob effect.");
        doc("on", "@on(\"event\", every=20)", "Register an event handler (player_join, block_break, tick ...).");
        doc("tex", "tex(\"ns:path\")", "Reference a texture (opens the asset picker with Ctrl+Space).");
        doc("snd", "snd(\"ns:event\")", "Reference a sound event.");
        doc("mdl", "mdl(\"ns:model\")", "Reference a model.");
        doc("loot_self", "loot_self()", "Drop the block/item itself when mined or dropped.");
        doc("recipe_shaped", "recipe_shaped(result, pattern, keys)", "Shaped crafting recipe from a pattern + key dict.");
        doc("recipe_shapeless", "recipe_shapeless(result, ingredients)", "Shapeless crafting recipe from an ingredient list.");
        doc("recipe_smelting", "recipe_smelting(result, ingredient, xp, cook_time)", "Furnace recipe (input -> result).");
        doc("recipe_blasting", "recipe_blasting(result, ingredient, xp, cook_time)", "Blast furnace recipe.");
        doc("recipe_smoking", "recipe_smoking(result, ingredient, xp, cook_time)", "Smoker recipe.");
        doc("recipe_campfire", "recipe_campfire(result, ingredient, cook_time)", "Campfire cooking recipe.");
        doc("recipe_stonecutting", "recipe_stonecutting(result, ingredient, count)", "Stonecutter recipe.");
        doc("recipe_smithing", "recipe_smithing(result, template, base, addition)", "Smithing table transform.");
        doc("loot_table", "loot_table(\"id\", entries=[...])", "Custom drops for a block or entity.");
        doc("loot_entry", "loot_entry(item, count=1, weight=1)", "One entry inside a loot_table.");
        doc("tag", "tag(\"#id\", values=[...])", "Add ids to a datapack tag (items, blocks ...).");
        doc("lang", "lang(\"key\", \"value\")", "Add a translation entry to en_us.json.");
        doc("advancement", "advancement(\"id\", **fields)", "Write a data-driven advancement.");
        doc("sound", "sound(\"event\", sounds=[...])", "Add an entry to the project's sounds.json.");
        doc("particle", "particle(\"id\", textures=[...])", "Declare a custom particle.");
        doc("enchantment", "@enchantment(\"id\", **fields)", "Declare a data-driven enchantment.");
        doc("potion", "potion(\"id\", base=..., effects=[...])", "Declare a potion variant.");
        doc("raw_json", "raw_json(\"path\", data)", "Escape hatch: raw datapack JSON (version-specific).");
        doc("every", "@every(20)", "Run a handler every N server ticks.");
        doc("on_load", "@on_load", "Run a handler when the project loads.");
        doc("on_unload", "@on_unload", "Run a handler when the project unloads.");
        doc("command", "@command(\"name\")", "Register a /craftpy subcommand (handler gets ctx.args).");
        doc("has", "has(\"capability\")", "True when an optional feature is available on this host.");
        doc("run_later", "run_later(ticks, fn)", "Run fn once after N ticks; returns a cancellable Task.");
        doc("run_every", "run_every(ticks, fn)", "Run fn every N ticks; returns a cancellable Task.");
        doc("run_next_tick", "run_next_tick(fn)", "Run fn on the next server tick.");
        doc("world", "world.get_block(x, y, z)", "World facade: get/set block, spawn, sound, time, weather.");
        doc("players", "players.send_message(name, msg)", "Player facade: all, get, send_message, teleport, give.");
        doc("server", "server.broadcast(msg)", "Server facade: broadcast, run_command, log.");
        doc("storage", "storage.set(key, value)", "Persistent per-scope key-value storage.");
        doc("scheduler", "scheduler.after(seconds, fn)", "Time-based scheduling built on the tick scheduler.");
        doc("window", "@window(\"id\", title=...)", "Declare a Python-described in-game window.");
        doc("ui", "ui.open(player, \"window\")", "Open/close a declared window for a player.");
        doc("main", "main(ctx)", "Optional entry point; ctx.log() writes to the console.");

        fields("block", List.of(
                new Field("name", "Display name shown in-game (tooltip / inventory)."),
                new Field("texture", "Block texture id, e.g. tex(\"project:ruby\")."),
                new Field("hardness", "Mining time; higher = slower to break (stone = 1.5)."),
                new Field("resistance", "Explosion resistance (stone = 6)."),
                new Field("light", "Light emitted, 0-15 (15 = glowstone)."),
                new Field("tool", "Preferred tool: pickaxe, axe, shovel, hoe or sword."),
                new Field("sound", "Step/placeholder sound set: stone, wood, grass, metal ..."),
                new Field("needs_tool", "True = drops require the right tool (like stone).")));

        fields("item", List.of(
                new Field("name", "Display name shown in-game."),
                new Field("texture", "Item texture id, e.g. tex(\"project:ruby\")."),
                new Field("max_stack", "Stack size, 1-64."),
                new Field("glow", "True = enchantment glint overlay."),
                new Field("food", "Dict {\"hunger\": 6, \"saturation\": 0.6} to make it edible."),
                new Field("burn_time", "Furnace fuel value in ticks; 0 = not fuel."),
                new Field("use_effect", "On right-click: \"effect[:amplifier:seconds]\", e.g. \"speed:2:10\".")));

        fields("entity", List.of(
                new Field("name", "Display name shown in-game."),
                new Field("texture", "Entity skin texture id."),
                new Field("base", "Base AI: mob_passive or mob_hostile."),
                new Field("health", "Max health (10 = half a heart bar)."),
                new Field("speed", "Movement speed, ~0.25 for a normal mob."),
                new Field("damage", "Attack damage for hostile bases."),
                new Field("width", "Hitbox width (0.6 = player-like)."),
                new Field("height", "Hitbox height (1.8 = player-like)."),
                new Field("follow_range", "How far the mob notices targets, in blocks.")));

        fields("effect", List.of(
                new Field("name", "Display name shown in-game."),
                new Field("icon", "Status icon texture id."),
                new Field("category", "good, neutral or bad (tinting/particle color)."),
                new Field("color", "Liquid/particle color, e.g. \"#8899ff\".")));
    }

    private static void doc(String name, String signature, String description) {
        DOCS.put(name, new Doc(signature, description));
    }

    private static void fields(String kind, List<Field> list) {
        FIELDS.put(kind, list);
    }

    private ApiDocs() {
    }

    /** Docs for an API name, or null when unknown (plain identifiers). */
    public static Doc get(String name) {
        return DOCS.get(name);
    }

    /** Declaration fields for a decorator kind ("block", "item", "entity", "effect"). */
    public static List<Field> fieldsFor(String kind) {
        List<Field> list = FIELDS.get(kind);
        return list == null ? List.of() : list;
    }
}
