package net.xmilon.craftpy.catalog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Turns a loot table (its JSON, as the game encodes it) into a flat list of what can drop: item or tag, count range,
 * chance per kill / break, the extra a looting / fortune level adds, and the conditions that matter to a player
 * (killed by a player, a bonus enchantment). Nested {@code loot_table} entries are followed through {@code resolver}.
 * Pure JSON: no Minecraft types, so it is unit-tested. Chances are what a player sees in practice, not a simulation:
 * {@code weight / total weight} of the pool, times the entry and pool conditions, over the pool's average rolls.
 */
public final class LootFlattener {
    /** One thing that can drop. {@code item} is an item id, or {@code #tag} for a tag entry. */
    public record Drop(String item, double min, double max, double chance, double bonusMin, double bonusMax,
                       boolean playerKill, List<String> notes) {
        public JsonObject toJson() {
            JsonObject out = new JsonObject();
            out.addProperty("item", item);
            out.addProperty("min", min);
            out.addProperty("max", max);
            out.addProperty("chance", chance);
            if (bonusMax > 0) {
                out.addProperty("bonus_min", bonusMin);
                out.addProperty("bonus_max", bonusMax);
            }
            if (playerKill) {
                out.addProperty("player_kill", true);
            }
            if (!notes.isEmpty()) {
                JsonArray array = new JsonArray();
                notes.forEach(array::add);
                out.add("notes", array);
            }
            return out;
        }
    }

    private static final int MAX_DEPTH = 8;

    private LootFlattener() {
    }

    public static List<Drop> flatten(JsonObject table, Function<String, JsonObject> resolver) {
        List<Drop> out = new ArrayList<>();
        table(table, resolver, 1.0, out, 0);
        return out;
    }

    private static void table(JsonObject table, Function<String, JsonObject> resolver, double scale, List<Drop> out, int depth) {
        if (table == null || depth > MAX_DEPTH || !table.has("pools") || !table.get("pools").isJsonArray()) {
            return;
        }
        Modifiers tableMods = Modifiers.of(table.get("functions"), null);
        for (JsonElement poolElement : table.getAsJsonArray("pools")) {
            if (!poolElement.isJsonObject()) {
                continue;
            }
            JsonObject pool = poolElement.getAsJsonObject();
            double[] rolls = range(pool.get("rolls"), 1, 1);
            double averageRolls = (rolls[0] + rolls[1]) / 2;
            Modifiers poolMods = Modifiers.of(pool.get("functions"), pool.get("conditions")).with(tableMods);
            JsonArray entries = pool.has("entries") && pool.get("entries").isJsonArray() ? pool.getAsJsonArray("entries") : new JsonArray();
            double total = 0;
            for (JsonElement entry : entries) {
                total += entry.isJsonObject() && entry.getAsJsonObject().has("weight") ? entry.getAsJsonObject().get("weight").getAsDouble() : 1;
            }
            if (total <= 0) {
                continue;
            }
            for (JsonElement entry : entries) {
                if (!entry.isJsonObject()) {
                    continue;
                }
                JsonObject e = entry.getAsJsonObject();
                double weight = e.has("weight") ? e.get("weight").getAsDouble() : 1;
                double perRoll = weight / total;
                entry(e, resolver, perRoll, averageRolls, scale, poolMods, out, depth);
            }
        }
    }

    private static void entry(JsonObject e, Function<String, JsonObject> resolver, double perRoll, double rolls, double scale,
                              Modifiers outer, List<Drop> out, int depth) {
        String type = strip(e.has("type") ? e.get("type").getAsString() : "");
        Modifiers mods = Modifiers.of(e.get("functions"), e.get("conditions")).with(outer);
        switch (type) {
            case "item", "tag" -> {
                String name = e.has("name") ? e.get("name").getAsString() : "?";
                double chanceRoll = perRoll * mods.chance;
                double chance = scale * (1 - Math.pow(1 - Math.min(1, chanceRoll), Math.max(1, rolls)));
                if (rolls < 1) {
                    chance = scale * chanceRoll * rolls;
                }
                out.add(new Drop("tag".equals(type) ? "#" + name : name, mods.min, mods.max, Math.min(1, chance),
                        mods.bonusMin, mods.bonusMax, mods.playerKill, List.copyOf(mods.notes)));
            }
            case "loot_table" -> {
                JsonElement value = e.get("value");
                JsonObject nested = value == null ? null
                        : value.isJsonObject() ? value.getAsJsonObject() : resolver.apply(value.getAsString());
                table(nested, resolver, scale * Math.min(1, perRoll * mods.chance * Math.max(1, rolls)), out, depth + 1);
            }
            case "alternatives", "group", "sequence" -> {
                if (e.has("children") && e.get("children").isJsonArray()) {
                    for (JsonElement child : e.getAsJsonArray("children")) {
                        if (child.isJsonObject()) {
                            entry(child.getAsJsonObject(), resolver, perRoll, rolls, scale, mods, out, depth + 1);
                        }
                    }
                }
            }
            default -> {
                // empty, dynamic (a block's own contents) and unknown entry types drop nothing to list
            }
        }
    }

    /** "minecraft:item" -> "item". */
    static String strip(String type) {
        int colon = type.indexOf(':');
        return colon >= 0 ? type.substring(colon + 1) : type;
    }

    /** A number provider as {min, max}: a number, {min,max}, uniform, constant, binomial. */
    static double[] range(JsonElement element, double fallbackMin, double fallbackMax) {
        if (element == null || element.isJsonNull()) {
            return new double[]{fallbackMin, fallbackMax};
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            double v = element.getAsDouble();
            return new double[]{v, v};
        }
        if (!element.isJsonObject()) {
            return new double[]{fallbackMin, fallbackMax};
        }
        JsonObject o = element.getAsJsonObject();
        String type = o.has("type") ? strip(o.get("type").getAsString()) : "uniform";
        return switch (type) {
            case "constant" -> {
                double v = o.has("value") ? o.get("value").getAsDouble() : fallbackMin;
                yield new double[]{v, v};
            }
            case "binomial" -> new double[]{0, range(o.get("n"), 1, 1)[1]};
            case "uniform" -> new double[]{range(o.get("min"), fallbackMin, fallbackMin)[0], range(o.get("max"), fallbackMax, fallbackMax)[1]};
            default -> new double[]{fallbackMin, fallbackMax}; // score / storage based: unknown here
        };
    }

    /** What functions and conditions do to an entry, accumulated from the table down to the entry. */
    private static final class Modifiers {
        double min = 1;
        double max = 1;
        boolean countSet;
        double chance = 1;
        double bonusMin;
        double bonusMax;
        boolean playerKill;
        final Set<String> notes = new LinkedHashSet<>();

        static Modifiers of(JsonElement functions, JsonElement conditions) {
            Modifiers m = new Modifiers();
            if (functions != null && functions.isJsonArray()) {
                for (JsonElement f : functions.getAsJsonArray()) {
                    if (f.isJsonObject()) {
                        m.function(f.getAsJsonObject());
                    }
                }
            }
            if (conditions != null && conditions.isJsonArray()) {
                for (JsonElement c : conditions.getAsJsonArray()) {
                    if (c.isJsonObject()) {
                        m.condition(c.getAsJsonObject());
                    }
                }
            }
            return m;
        }

        /** The outer level's effects under this one (an inner set_count wins over an outer one). */
        Modifiers with(Modifiers outer) {
            if (!countSet && outer.countSet) {
                min = outer.min;
                max = outer.max;
                countSet = true;
            }
            chance *= outer.chance;
            bonusMin = Math.max(bonusMin, outer.bonusMin);
            bonusMax = Math.max(bonusMax, outer.bonusMax);
            playerKill |= outer.playerKill;
            notes.addAll(outer.notes);
            return this;
        }

        private void function(JsonObject f) {
            String type = strip(f.has("function") ? f.get("function").getAsString() : "");
            switch (type) {
                case "set_count" -> {
                    double[] r = range(f.get("count"), 1, 1);
                    boolean add = f.has("add") && f.get("add").getAsBoolean();
                    min = add ? min + r[0] : r[0];
                    max = add ? max + r[1] : r[1];
                    countSet = true;
                }
                case "enchanted_count_increase", "looting_enchant" -> {
                    double[] r = range(f.get("count"), 0, 1);
                    bonusMin = r[0];
                    bonusMax = r[1];
                    notes.add("more with " + (f.has("enchantment") ? f.get("enchantment").getAsString() : "Looting"));
                }
                case "apply_bonus" -> notes.add("more with " + (f.has("enchantment") ? f.get("enchantment").getAsString() : "Fortune"));
                case "furnace_smelt" -> notes.add("cooked when the mob burns");
                case "set_components", "set_nbt", "set_custom_data" -> notes.add("with extra data");
                case "enchant_randomly", "enchant_with_levels", "set_enchantments" -> notes.add("enchanted");
                default -> {
                }
            }
        }

        private void condition(JsonObject c) {
            String type = strip(c.has("condition") ? c.get("condition").getAsString() : "");
            switch (type) {
                case "random_chance" -> chance *= range(c.get("chance"), 1, 1)[0];
                case "random_chance_with_enchanted_bonus", "random_chance_with_looting" -> {
                    JsonElement base = c.has("unenchanted_chance") ? c.get("unenchanted_chance") : c.get("chance");
                    chance *= base == null ? 1 : range(base, 1, 1)[0];
                    notes.add("likelier with Looting");
                }
                case "killed_by_player" -> playerKill = true;
                case "survives_explosion" -> {
                }
                case "inverted", "any_of", "all_of", "alternative" -> notes.add("only sometimes (conditions)");
                default -> notes.add("only when: " + type.replace('_', ' '));
            }
        }
    }
}
