package net.xmilon.craftpy.content;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Typed views over a JsonObject "definition" produced by the Python worker.
 * Every field has a sane default so a minimal script still yields a usable
 * block/item.
 */
public final class Defs {
    private Defs() {
    }

    /** Read a string with a default. Unwraps {@code {"ref":..,"value":..}} assets. */
    public static String s(JsonObject json, String key, String fallback) {
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (element.isJsonObject()) {
            JsonElement value = element.getAsJsonObject().get("value");
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                String ref = value.getAsString();
                return ref.isBlank() ? fallback : ref;
            }
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return fallback;
        }
        String value = element.getAsString();
        return value.isBlank() ? fallback : value;
    }

    public static float f(JsonObject json, String key, float fallback) {
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        return element.getAsFloat();
    }

    public static int i(JsonObject json, String key, int fallback) {
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        return element.getAsInt();
    }

    public static boolean b(JsonObject json, String key, boolean fallback) {
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isBoolean()) {
            return fallback;
        }
        return element.getAsBoolean();
    }

    /** The data of the {@code extensions} the Python side attached ({@code definition.ext}). */
    static List<JsonObject> extensions(JsonObject json) {
        List<JsonObject> out = new ArrayList<>();
        JsonElement element = json.get("ext");
        if (element != null && element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement entry : array) {
                if (entry.isJsonObject()) {
                    out.add(entry.getAsJsonObject());
                }
            }
        }
        return out;
    }

    public static final class BlockDef {
        public String name;
        public String texture;
        public String sound;
        public String tool;
        public boolean needsTool;
        public float hardness;
        public float resistance;
        public int light;
        /** Lowest tool mining level that mines this block (MineableBy tier); 0 = any. */
        public int minLevel;
        /** Redstone signal strength emitted, 0 = none. */
        public int redstone;
        /** Fire: chance to catch fire / spread speed; chance &lt; 0 = not flammable. */
        public int burnChance = -1;
        public int burnSpread;
        /** Slipperiness; negative = vanilla default. */
        public float friction = -1f;
        /** "normal", "destroy", "block", "ignore", "push_only" or "" for the default. */
        public String push = "";

        public static BlockDef from(JsonObject json) {
            BlockDef def = new BlockDef();
            def.name = s(json, "name", "CraftPy Block");
            def.texture = s(json, "texture", "minecraft:block/stone");
            def.sound = s(json, "sound", "");
            def.tool = s(json, "tool", "");
            def.needsTool = b(json, "needs_tool", false);
            def.hardness = f(json, "hardness", 2.0f);
            def.resistance = f(json, "resistance", 6.0f);
            def.light = Math.max(0, Math.min(15, i(json, "light", 0)));
            for (JsonObject ext : extensions(json)) {
                switch (s(ext, "type", "")) {
                    case "mineable" -> def.minLevel = Math.max(0, Math.min(4, i(ext, "level", 0)));
                    case "redstone" -> def.redstone = Math.max(0, Math.min(15, i(ext, "power", 0)));
                    case "flammable" -> {
                        def.burnChance = Math.max(0, Math.min(300, i(ext, "chance", 5)));
                        def.burnSpread = Math.max(0, Math.min(300, i(ext, "spread", 20)));
                    }
                    case "friction" -> def.friction = Math.max(0.1f, Math.min(1.0f, f(ext, "value", 0.6f)));
                    case "piston" -> def.push = s(ext, "reaction", "");
                    default -> {
                    }
                }
            }
            return def;
        }
    }

    public static final class ItemDef {
        public String name;
        public String texture;
        public int maxStack;
        public boolean glow;
        /** Data components of any mod (JSON), filled into every stack that lacks them (SlottedItem.inventoryTick). */
        public com.google.gson.JsonObject components;
        /** null = not edible. */
        public net.minecraft.world.food.FoodProperties food;
        /** Seconds worth of furnace burn time; 0 = not fuel. */
        public int burnTime;
        /** "effect[:amplifier:seconds]" applied on use, can be namespaced. */
        public String useEffect;
        public ToolSpec tool;
        public WeaponSpec weapon;
        public ArmorSpec armor;
        /** Uses before the item breaks; 0 = no durability. */
        public int maxDamage;
        /** Item id that repairs it in an anvil, "" = none. */
        public String repairWith = "";
        /** Enchantment value, negative = default. */
        public int enchantValue = -1;
        public float cooldownSeconds;
        public final List<String> tooltip = new ArrayList<>();
        public String tooltipColor = "gray";
        /** Attribute modifiers while held or worn (Attributes extension): any attribute of the game or of a mod. */
        public final List<AttributeSpec> attributes = new ArrayList<>();

        public static ItemDef from(JsonObject json) {
            ItemDef def = new ItemDef();
            def.name = s(json, "name", "CraftPy Item");
            def.texture = s(json, "texture", "minecraft:item/paper");
            def.maxStack = Math.max(1, Math.min(64, i(json, "max_stack", 64)));
            def.glow = b(json, "glow", false);
            def.food = foodFrom(json);
            def.burnTime = Math.max(0, i(json, "burn_time", 0));
            def.useEffect = effectFrom(json);
            def.components = json.has("components") && json.get("components").isJsonObject() ? json.getAsJsonObject("components") : null;
            for (JsonObject ext : extensions(json)) {
                switch (s(ext, "type", "")) {
                    case "tool" -> def.tool = new ToolSpec(s(ext, "kind", "pickaxe"), i(ext, "level", 0),
                            f(ext, "speed", 1f), f(ext, "damage", 1f), f(ext, "attack_speed", 1.6f));
                    case "weapon" -> def.weapon = new WeaponSpec(f(ext, "damage", 1f), f(ext, "attack_speed", 4f),
                            f(ext, "knockback", 0f));
                    case "armor" -> def.armor = new ArmorSpec(s(ext, "slot", "chest"), f(ext, "defense", 0f),
                            f(ext, "toughness", 0f), f(ext, "knockback_resistance", 0f));
                    case "durability" -> {
                        def.maxDamage = Math.max(0, Math.min(32767, i(ext, "uses", 0)));
                        def.repairWith = s(ext, "repair_with", "");
                    }
                    case "attributes" -> {
                        if (def.attributes.size() < 16 && !s(ext, "attribute", "").isEmpty()) {
                            def.attributes.add(new AttributeSpec(s(ext, "attribute", ""), f(ext, "amount", 0f),
                                    s(ext, "operation", "add_value"), s(ext, "slot", "mainhand")));
                        }
                    }
                    case "enchantable" -> def.enchantValue = Math.max(0, Math.min(50, i(ext, "value", 0)));
                    case "cooldown" -> def.cooldownSeconds = Math.max(0f, f(ext, "seconds", 0f));
                    case "tooltip" -> {
                        def.tooltipColor = s(ext, "color", "gray");
                        JsonElement lines = ext.get("lines");
                        if (lines != null && lines.isJsonArray()) {
                            for (JsonElement line : lines.getAsJsonArray()) {
                                if (line.isJsonPrimitive() && def.tooltip.size() < 10) {
                                    def.tooltip.add(line.getAsString());
                                }
                            }
                        }
                    }
                    default -> {
                    }
                }
            }
            return def;
        }

        /** Mining and fighting values of a tool; level 0-4 (wood/gold, stone, iron, diamond, netherite). */
        public record ToolSpec(String kind, int level, float speed, float damage, float attackSpeed) {
        }

        public record WeaponSpec(float damage, float attackSpeed, float knockback) {
        }

        public record ArmorSpec(String slot, float defense, float toughness, float knockbackResistance) {
        }

        /** One modifier: attribute id, amount, operation (add_value, add_multiplied_base, add_multiplied_total), slot group. */
        public record AttributeSpec(String attribute, double amount, String operation, String slot) {
        }

        private static net.minecraft.world.food.FoodProperties foodFrom(JsonObject json) {
            JsonElement element = json.get("food");
            if (element == null || !element.isJsonObject()) {
                return null;
            }
            JsonObject food = element.getAsJsonObject();
            net.minecraft.world.food.FoodProperties.Builder builder =
                    new net.minecraft.world.food.FoodProperties.Builder()
                            .nutrition(Math.max(1, i(food, "hunger", 6)))
                            .saturationModifier(f(food, "saturation", 0.6f));
            if (b(food, "always_eat", false)) {
                builder = builder.alwaysEdible();
            }
            if (b(food, "fast", false)) {
                builder = builder.fast();
            }
            return builder.build();
        }

        private static String effectFrom(JsonObject json) {
            String value = s(json, "use_effect", "");
            return value.isBlank() ? null : value.trim();
        }
    }

    /**
     * A declared mob effect: name, icon, color and category, plus attribute modifiers per level while it is active.
     * {@code @on.effect_tick} handlers in Python do the rest.
     */
    public static final class EffectDef {
        public String name;
        public String icon;
        public String category;
        public int color;
        /** Amount is per level: level 2 gives twice the amount. The slot field is unused. */
        public final List<ItemDef.AttributeSpec> attributes = new ArrayList<>();

        public static EffectDef from(JsonObject json) {
            EffectDef def = new EffectDef();
            def.name = s(json, "name", "CraftPy Effect");
            def.icon = s(json, "icon", "");
            def.category = s(json, "category", "neutral").toLowerCase(java.util.Locale.ROOT);
            def.color = parseColor(s(json, "color", "#8899ff"));
            if (json.get("attributes") instanceof JsonArray list) {
                for (JsonElement element : list) {
                    if (element.isJsonObject() && def.attributes.size() < 16) {
                        JsonObject a = element.getAsJsonObject();
                        if (!s(a, "attribute", "").isEmpty()) {
                            def.attributes.add(new ItemDef.AttributeSpec(s(a, "attribute", ""), f(a, "amount", 0f),
                                    s(a, "operation", "add_value"), ""));
                        }
                    }
                }
            }
            return def;
        }
    }

    /**
     * A declared mob: a generic {@code PathfinderMob} with these stats, a vanilla body ({@code model="pig"}) or a
     * GeckoLib model ({@code model="goblin.geo.json"}), a spawn egg and optional natural spawns.
     */
    public static final class EntityDef {
        public String name;
        public String texture;
        public String model;
        public String animation;
        public float scale;
        public String base;
        public float health;
        public float speed;
        public float damage;
        public float armor;
        public float width;
        public float height;
        public float followRange;
        public int eggColor;
        public int eggSpots;
        public final List<Spawn> spawns = new ArrayList<>();

        /** Natural spawning in biomes ({@code "#minecraft:is_overworld"} or {@code "minecraft:plains"}). */
        public record Spawn(List<String> biomes, int weight, int min, int max) {
        }

        public static EntityDef from(JsonObject json) {
            EntityDef def = new EntityDef();
            def.name = s(json, "name", "CraftPy Entity");
            def.texture = s(json, "texture", "");
            def.model = s(json, "model", "humanoid");
            def.animation = s(json, "animation", "");
            def.scale = Math.max(0.05f, Math.min(16f, f(json, "scale", 1.0f)));
            def.base = s(json, "base", "passive").toLowerCase(java.util.Locale.ROOT);
            def.health = Math.max(1.0f, f(json, "health", 10.0f));
            def.speed = f(json, "speed", 0.25f);
            def.damage = f(json, "damage", 2.0f);
            def.armor = Math.max(0f, f(json, "armor", 0f));
            def.width = Math.max(0.1f, Math.min(16f, f(json, "width", 0.6f)));
            def.height = Math.max(0.1f, Math.min(16f, f(json, "height", 1.8f)));
            def.followRange = Math.max(1.0f, f(json, "follow_range", 16.0f));
            def.eggColor = parseColor(s(json, "egg_color", "#3d8f5a"));
            def.eggSpots = parseColor(s(json, "egg_spots", "#c9e86b"));
            if (json.get("spawns") instanceof JsonArray list) {
                for (JsonElement element : list) {
                    if (!element.isJsonObject() || def.spawns.size() >= 16) {
                        continue;
                    }
                    JsonObject spawn = element.getAsJsonObject();
                    List<String> biomes = new ArrayList<>();
                    if (spawn.get("biomes") instanceof JsonArray names) {
                        names.forEach(name -> biomes.add(name.getAsString()));
                    } else {
                        biomes.add(s(spawn, "biomes", "#minecraft:is_overworld"));
                    }
                    int min = Math.max(1, i(spawn, "min", 1));
                    def.spawns.add(new Spawn(biomes, Math.max(1, i(spawn, "weight", 10)), min,
                            Math.max(min, i(spawn, "max", 3))));
                }
            }
            return def;
        }

        public boolean hostile() {
            return base.contains("hostile") || base.contains("monster") || base.contains("enemy");
        }

        /** A GeckoLib model ({@code .geo.json}) instead of a vanilla body. */
        public boolean geckoModel() {
            return model.endsWith(".geo.json");
        }
    }

    /** Parses "#rrggbb", "0xrrggbb" or a bare integer into an RGB int. */
    public static int parseColor(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0x8899ff;
        }
        String value = raw.trim();
        try {
            if (value.startsWith("#")) {
                return (int) Long.parseLong(value.substring(1), 16) & 0xFFFFFF;
            }
            if (value.startsWith("0x") || value.startsWith("0X")) {
                return (int) Long.parseLong(value.substring(2), 16) & 0xFFFFFF;
            }
            return (int) Long.parseLong(value) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return 0x8899ff;
        }
    }
}