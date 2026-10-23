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
        public KineticDef kinetic;
        public JsonObject energy;
        public JsonObject fluid;
        /** The Minecraft-style window of a machine block (Screen(...)); null = none. */
        public JsonObject screen;
        /** Own mesh: a model of the project's assets/models/block/ ("" = the generated cube). */
        public String model = "";
        /** Hitbox (outline and collision); null = a full cube. */
        public net.minecraft.world.phys.shapes.VoxelShape shape;

        public boolean machine() { return kinetic != null || energy != null || fluid != null || screen != null; }

        /**
         * The hitbox of an imported model: the union of its {@code elements} boxes (a Blockbench "Java Block/Item" export),
         * clamped to the block. Null when the file is missing, unreadable or has no elements. Rotations are not applied.
         */
        public static net.minecraft.world.phys.shapes.VoxelShape shapeOfModel(java.nio.file.Path file) {
            try {
                JsonObject model = com.google.gson.JsonParser.parseString(
                        java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                if (!model.has("elements") || !model.get("elements").isJsonArray()) {
                    return null;
                }
                JsonArray boxes = new JsonArray();
                for (JsonElement element : model.getAsJsonArray("elements")) {
                    if (boxes.size() >= 64 || !element.isJsonObject()) {
                        continue;
                    }
                    JsonObject e = element.getAsJsonObject();
                    if (!e.has("from") || !e.has("to") || e.getAsJsonArray("from").size() != 3
                            || e.getAsJsonArray("to").size() != 3) {
                        continue;
                    }
                    JsonArray box = new JsonArray();
                    for (String key : new String[]{"from", "to"}) {
                        for (JsonElement n : e.getAsJsonArray(key)) {
                            box.add(n);
                        }
                    }
                    boxes.add(box);
                }
                JsonObject wrapper = new JsonObject();
                wrapper.add("shape", boxes);
                return shapeOf(wrapper);
            } catch (java.io.IOException | RuntimeException unreadable) {
                return null;
            }
        }

        /** The "shape" boxes (sixteenths of a block) as one shape; null when absent or empty. */
        private static net.minecraft.world.phys.shapes.VoxelShape shapeOf(JsonObject json) {
            if (!json.has("shape") || !json.get("shape").isJsonArray()) {
                return null;
            }
            net.minecraft.world.phys.shapes.VoxelShape shape = net.minecraft.world.phys.shapes.Shapes.empty();
            for (JsonElement element : json.getAsJsonArray("shape")) {
                if (!element.isJsonArray() || element.getAsJsonArray().size() != 6) {
                    continue;
                }
                JsonArray box = element.getAsJsonArray();
                double[] n = new double[6];
                for (int k = 0; k < 6; k++) {
                    n[k] = Math.max(0, Math.min(16, box.get(k).getAsDouble())) / 16.0;
                }
                shape = net.minecraft.world.phys.shapes.Shapes.or(shape,
                        net.minecraft.world.phys.shapes.Shapes.box(n[0], n[1], n[2], n[3], n[4], n[5]));
            }
            return shape.isEmpty() ? null : shape;
        }

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
            def.model = s(json, "model", "");
            def.shape = shapeOf(json);
            for (JsonObject ext : extensions(json)) {
                switch (s(ext, "type", "")) {
                    case "kinetic" -> def.kinetic = KineticDef.from(ext);
                    case "energy_storage" -> def.energy = MachineState.validateEnergy(ext);
                    case "fluid_tank" -> def.fluid = MachineState.validateFluid(ext);
                    case "screen" -> def.screen = ext.deepCopy();
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
    /** A declared liquid (Fluid(...)): how it flows and looks, and what it does to whoever stands in it. */
    public static final class FluidDef {
        /** An effect applied while an entity is in the liquid. */
        public record Touch(net.minecraft.resources.ResourceLocation effect, int amplifier, int seconds) {
        }

        public String name;
        public int color;
        public int density = 1000, viscosity = 1000, temperature = 300, light;
        /** The liquid's own textures (project assets), or null for the water ones tinted with {@link #color}. */
        public net.minecraft.resources.ResourceLocation still, flowing;
        public final List<Touch> effects = new ArrayList<>();
        /** Texture of the bucket (project assets or a vanilla one); "" = the water bucket. */
        public String bucket = "";

        /** "name.png" of the project's assets/textures/ as the atlas location {@code project:name}; null when empty. */
        private static net.minecraft.resources.ResourceLocation textureOf(String text, String projectId) {
            if (text.isBlank()) {
                return null;
            }
            String clean = text.endsWith(".png") ? text.substring(0, text.length() - 4) : text;
            return net.minecraft.resources.ResourceLocation.tryParse(clean.contains(":") ? clean : projectId + ":" + clean);
        }

        public static FluidDef from(JsonObject json, String projectId) {
            FluidDef def = new FluidDef();
            def.name = s(json, "name", "CraftPy Liquid");
            def.color = 0xFF000000 | parseColor(s(json, "color", "#3fa7ff"));
            def.density = Math.max(-20000, Math.min(20000, i(json, "density", 1000)));
            def.viscosity = Math.max(0, Math.min(20000, i(json, "viscosity", 1000)));
            def.temperature = Math.max(0, Math.min(5000, i(json, "temperature", 300)));
            def.light = Math.max(0, Math.min(15, i(json, "light", 0)));
            def.bucket = s(json, "bucket", "");
            def.still = textureOf(s(json, "still", ""), projectId);
            def.flowing = textureOf(s(json, "flowing", ""), projectId);
            if (json.get("effects") instanceof JsonArray list) {
                for (JsonElement element : list) {
                    if (def.effects.size() >= 8 || !element.isJsonPrimitive()) {
                        continue;
                    }
                    String spec = element.getAsString(); // id:amplifier:seconds, the id may hold a colon
                    int last = spec.lastIndexOf(':'), middle = last < 0 ? -1 : spec.lastIndexOf(':', last - 1);
                    if (middle < 0) {
                        continue;
                    }
                    try {
                        var id = net.minecraft.resources.ResourceLocation.tryParse(spec.substring(0, middle));
                        if (id != null) {
                            def.effects.add(new Touch(id, Math.max(0, Math.min(255, Integer.parseInt(spec.substring(middle + 1, last)))),
                                    Math.max(1, Math.min(600, Integer.parseInt(spec.substring(last + 1))))));
                        }
                    } catch (NumberFormatException ignored) {
                        // a malformed effect is skipped
                    }
                }
            }
            return def;
        }
    }

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
        public JsonObject behavior = new JsonObject();
        public JsonArray goals = new JsonArray();
        public JsonObject animations = new JsonObject();

        /** Natural spawning in biomes ({@code "#minecraft:is_overworld"} or {@code "minecraft:plains"}). */
        public record Spawn(List<String> biomes, int weight, int min, int max) {
        }

        public static EntityDef from(JsonObject json) {
            EntityDef def = new EntityDef();
            def.name = s(json, "name", "CraftPy Entity");
            def.texture = s(json, "texture", "");
            def.model = s(json, "model", "humanoid");
            def.animation = s(json, "animation", "");
            if (json.has("behavior")) {
                def.behavior = json.getAsJsonObject("behavior").deepCopy();
                String profile = s(def.behavior, "profile", "");
                if (!java.util.Set.of("melee", "ranged", "follow", "guard").contains(profile)) throw new IllegalArgumentException("Unknown mob behavior profile: " + profile);
                String target = s(def.behavior, "target", "hostile");
                if (!java.util.Set.of("hostile", "players", "none").contains(target)) throw new IllegalArgumentException("Unknown mob target: " + target);
                for (String key : def.behavior.keySet()) {
                    if (java.util.Set.of("profile", "target").contains(key)) continue;
                    if (!java.util.Set.of("speed", "range", "interval", "radius").contains(key)) throw new IllegalArgumentException("Unknown behavior option: " + key);
                    double value = def.behavior.get(key).getAsDouble();
                    double min = key.equals("interval") ? 10 : key.equals("speed") ? 0.1 : 1;
                    double max = key.equals("interval") ? 200 : key.equals("speed") ? 4 : 64;
                    if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException("Behavior " + key + " must be " + min + "-" + max);
                }
            }
            if (json.has("goals")) def.goals = json.getAsJsonArray("goals").deepCopy();
            if (json.has("animations")) {
                def.animations = json.getAsJsonObject("animations").deepCopy();
                for (var entry : def.animations.entrySet()) {
                    if (!java.util.Set.of("idle", "walk", "run", "attack", "cast", "hurt", "death").contains(entry.getKey())
                            || !entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()
                            || entry.getValue().getAsString().length() > 128) throw new IllegalArgumentException("Invalid named animation: " + entry.getKey());
                }
            }
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
