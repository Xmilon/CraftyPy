package net.xmilon.craftpy.content;

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

    public static final class BlockDef {
        public String name;
        public String texture;
        public String sound;
        public String tool;
        public boolean needsTool;
        public float hardness;
        public float resistance;
        public int light;

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
            return def;
        }
    }

    public static final class ItemDef {
        public String name;
        public String texture;
        public int maxStack;
        public boolean glow;
        /** null = not edible. */
        public net.minecraft.world.food.FoodProperties food;
        /** Seconds worth of furnace burn time; 0 = not fuel. */
        public int burnTime;
        /** "effect[:amplifier:seconds]" applied on use, can be namespaced. */
        public String useEffect;

        public static ItemDef from(JsonObject json) {
            ItemDef def = new ItemDef();
            def.name = s(json, "name", "CraftPy Item");
            def.texture = s(json, "texture", "minecraft:item/paper");
            def.maxStack = Math.max(1, Math.min(64, i(json, "max_stack", 64)));
            def.glow = b(json, "glow", false);
            def.food = foodFrom(json);
            def.burnTime = Math.max(0, i(json, "burn_time", 0));
            def.useEffect = effectFrom(json);
            return def;
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
     * A declared mob effect. Behaviour is a marker (no attribute modifiers in
     * v1); name, colour, category and icon are data-driven.
     */
    public static final class EffectDef {
        public String name;
        public String icon;
        public String category;
        public int color;

        public static EffectDef from(JsonObject json) {
            EffectDef def = new EffectDef();
            def.name = s(json, "name", "CraftPy Effect");
            def.icon = s(json, "icon", "");
            def.category = s(json, "category", "neutral");
            def.color = parseColor(s(json, "color", "#8899ff"));
            return def;
        }
    }

    /**
     * A declared entity. v1 reskins a generic {@code PathfinderMob} with
     * configurable stats; there is no custom Java AI.
     */
    public static final class EntityDef {
        public String name;
        public String texture;
        public String base;
        public float health;
        public float speed;
        public float damage;
        public float width;
        public float height;
        public float followRange;

        public static EntityDef from(JsonObject json) {
            EntityDef def = new EntityDef();
            def.name = s(json, "name", "CraftPy Entity");
            def.texture = s(json, "texture", "");
            def.base = s(json, "base", "passive").toLowerCase(java.util.Locale.ROOT);
            def.health = Math.max(1.0f, f(json, "health", 10.0f));
            def.speed = f(json, "speed", 0.25f);
            def.damage = f(json, "damage", 2.0f);
            def.width = Math.max(0.1f, f(json, "width", 0.6f));
            def.height = Math.max(0.1f, f(json, "height", 1.8f));
            def.followRange = Math.max(1.0f, f(json, "follow_range", 16.0f));
            return def;
        }

        public boolean hostile() {
            return base.contains("hostile") || base.contains("monster") || base.contains("enemy");
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