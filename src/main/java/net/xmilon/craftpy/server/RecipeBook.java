package net.xmilon.craftpy.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.xmilon.craftpy.catalog.LootFlattener;
import net.xmilon.craftpy.util.CraftPyExecutors;

/**
 * The data behind the IDE's Recipes & drops window, read from the running server so every mod is included: recipes
 * of every type (crafting, smelting, Create, Mekanism...) as generic cards, and every loot table flattened into drops
 * (mobs, blocks, chests, fishing) - mob drops are what JEI does not show. Answers are queries for one item or one mob,
 * never the whole book, so a reply stays small.
 */
final class RecipeBook {
    private static final int MAX_RECIPES = 150;
    private static final int MAX_SOURCES = 300;

    /** item id -> where it drops; rebuilt when the server's loot tables change (/reload). */
    private static volatile Object indexedFor;
    private static volatile Map<String, List<JsonObject>> dropIndex = Map.of();
    private static volatile Map<String, JsonObject> tableJson = Map.of();

    private RecipeBook() {
    }

    static CompletableFuture<JsonObject> recipes(JsonObject request) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return CompletableFuture.completedFuture(error("recipe_book", "Recipes need a running world"));
        }
        String itemId = str(request, "item");
        return server.submit(() -> {
            Item item = item(itemId);
            if (item == null) {
                return error("recipe_book", "No item " + itemId);
            }
            ItemStack wanted = new ItemStack(item);
            RegistryAccess access = server.registryAccess();
            RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, access);
            JsonArray madeBy = new JsonArray();
            JsonArray usedIn = new JsonArray();
            int[] found = new int[2];
            for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
                Recipe<?> recipe = holder.value();
                ItemStack result;
                try {
                    result = recipe.getResultItem(access);
                } catch (RuntimeException e) {
                    result = ItemStack.EMPTY; // special recipes (map cloning...) have no fixed result
                }
                JsonObject encoded = encoded(server, ops, holder);
                boolean makes = result.is(item) || extraOutputs(encoded).stream().anyMatch(o -> itemId(item).equals(o.get("item").getAsString()));
                boolean uses = recipe.getIngredients().stream().anyMatch(ingredient -> !ingredient.isEmpty() && ingredient.test(wanted));
                if (makes && found[0]++ < MAX_RECIPES) {
                    madeBy.add(card(holder, recipe, result, encoded));
                }
                if (uses && found[1]++ < MAX_RECIPES) {
                    usedIn.add(card(holder, recipe, result, encoded));
                }
            }
            JsonObject reply = new JsonObject();
            reply.addProperty("op", "recipe_book");
            reply.addProperty("item", itemId(item));
            reply.addProperty("made_by_total", found[0]);
            reply.addProperty("used_in_total", found[1]);
            reply.add("made_by", madeBy);
            reply.add("used_in", usedIn);
            return reply;
        });
    }

    static CompletableFuture<JsonObject> drops(JsonObject request) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return CompletableFuture.completedFuture(error("loot_book", "Drops need a running world"));
        }
        String mob = str(request, "mob");
        String itemId = str(request, "item");
        ReloadableServerRegistries.Holder registries = server.reloadableRegistries();
        RegistryAccess access = server.registryAccess();
        return CraftPyExecutors.supplyAsync(() -> {
            index(registries, access);
            JsonObject reply = new JsonObject();
            reply.addProperty("op", "loot_book");
            if (!mob.isEmpty()) {
                ResourceLocation id = ResourceLocation.tryParse(mob);
                var type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
                if (type == null) {
                    return error("loot_book", "No mob " + mob);
                }
                String table = type.getDefaultLootTable().location().toString();
                reply.addProperty("mob", id.toString());
                reply.addProperty("table", table);
                JsonArray drops = new JsonArray();
                JsonObject json = tableJson.get(table);
                if (json != null) {
                    for (LootFlattener.Drop drop : LootFlattener.flatten(json, tableJson::get)) {
                        drops.add(drop.toJson());
                    }
                }
                reply.add("drops", drops);
            } else {
                Item item = item(itemId);
                if (item == null) {
                    return error("loot_book", "No item " + itemId);
                }
                List<JsonObject> sources = new ArrayList<>(dropIndex.getOrDefault(itemId(item), List.of()));
                for (var tag : new ItemStack(item).getTags().toList()) {
                    sources.addAll(dropIndex.getOrDefault("#" + tag.location(), List.of()));
                }
                JsonArray array = new JsonArray();
                sources.stream().limit(MAX_SOURCES).forEach(array::add);
                reply.addProperty("item", itemId(item));
                reply.addProperty("total", sources.size());
                reply.add("sources", array);
            }
            return reply;
        }, CraftPyExecutors.io());
    }

    /** Encodes every loot table once per reload and indexes what drops where. Loot tables are immutable once loaded. */
    private static synchronized void index(ReloadableServerRegistries.Holder registries, RegistryAccess access) {
        if (indexedFor == registries) {
            return;
        }
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, access);
        Map<String, JsonObject> tables = new HashMap<>();
        for (ResourceLocation id : registries.getKeys(Registries.LOOT_TABLE)) {
            LootTable table = registries.getLootTable(ResourceKey.create(Registries.LOOT_TABLE, id));
            LootTable.DIRECT_CODEC.encodeStart(ops, table).result()
                    .filter(JsonElement::isJsonObject).ifPresent(json -> tables.put(id.toString(), json.getAsJsonObject()));
        }
        Map<String, List<JsonObject>> index = new HashMap<>();
        for (var entry : tables.entrySet()) {
            String table = entry.getKey();
            for (LootFlattener.Drop drop : LootFlattener.flatten(entry.getValue(), tables::get)) {
                JsonObject source = new JsonObject();
                source.addProperty("table", table);
                source.addProperty("kind", kind(table));
                source.addProperty("source", source(table));
                source.add("drop", drop.toJson());
                index.computeIfAbsent(drop.item(), k -> new ArrayList<>()).add(source);
            }
        }
        // mobs first, then blocks, chests, the rest: what a player looks for most
        index.values().forEach(list -> list.sort(java.util.Comparator.comparingInt(s -> order(s.get("kind").getAsString()))));
        tableJson = tables;
        dropIndex = index;
        indexedFor = registries;
    }

    private static int order(String kind) {
        return switch (kind) {
            case "mob" -> 0;
            case "block" -> 1;
            case "chest" -> 2;
            default -> 3;
        };
    }

    /** "minecraft:entities/zombie" -> mob, blocks -> block, chests -> chest, everything else -> other. */
    static String kind(String table) {
        String path = table.substring(table.indexOf(':') + 1);
        if (path.startsWith("entities/")) {
            return "mob";
        }
        if (path.startsWith("blocks/")) {
            return "block";
        }
        return path.startsWith("chests/") ? "chest" : "other";
    }

    /** "minecraft:entities/zombie" -> "minecraft:zombie"; other tables keep their id. */
    static String source(String table) {
        int colon = table.indexOf(':');
        String ns = table.substring(0, colon + 1);
        String path = table.substring(colon + 1);
        for (String prefix : new String[]{"entities/", "blocks/"}) {
            if (path.startsWith(prefix)) {
                return ns + path.substring(prefix.length());
            }
        }
        return table;
    }

    private static JsonObject card(RecipeHolder<?> holder, Recipe<?> recipe, ItemStack result, JsonObject encoded) {
        JsonObject card = new JsonObject();
        card.addProperty("id", holder.id().toString());
        ResourceLocation type = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        card.addProperty("type", type == null ? "?" : type.toString());
        if (recipe instanceof net.minecraft.world.item.crafting.ShapedRecipe shaped) {
            card.addProperty("width", shaped.getWidth());
            card.addProperty("height", shaped.getHeight());
        }
        JsonArray inputs = new JsonArray();
        for (Ingredient ingredient : recipe.getIngredients()) {
            JsonArray choices = new JsonArray();
            for (ItemStack stack : ingredient.getItems()) {
                if (choices.size() < 24) {
                    choices.add(itemId(stack.getItem()) + (stack.getCount() > 1 ? " x" + stack.getCount() : ""));
                }
            }
            inputs.add(choices);
        }
        card.add("inputs", inputs);
        JsonObject output = new JsonObject();
        output.addProperty("item", result.isEmpty() ? "" : itemId(result.getItem()));
        output.addProperty("count", result.getCount());
        card.add("output", output);
        JsonArray extras = new JsonArray();
        for (JsonObject extra : extraOutputs(encoded)) {
            if (!extra.get("item").getAsString().equals(output.get("item").getAsString()) || extra.has("chance")) {
                extras.add(extra);
            }
        }
        if (!extras.isEmpty()) {
            card.add("extra_outputs", extras);
        }
        JsonArray fluids = new JsonArray();
        collectFluids(encoded, fluids, 0);
        if (!fluids.isEmpty()) {
            card.add("fluids", fluids);
        }
        if (encoded != null && encoded.has("processing_time")) {
            card.addProperty("time", encoded.get("processing_time").getAsDouble());
        } else if (encoded != null && encoded.has("cookingtime")) {
            card.addProperty("time", encoded.get("cookingtime").getAsDouble());
        }
        return card;
    }

    /** recipe id -> its encoded JSON (null when it cannot be encoded), kept until the recipes reload. */
    private static volatile Object encodedFor;
    private static final Map<ResourceLocation, java.util.Optional<JsonObject>> ENCODED = new java.util.concurrent.ConcurrentHashMap<>();

    /** Server thread. Encoding every recipe on every query would be slow in a big pack, so each is encoded once. */
    private static JsonObject encoded(MinecraftServer server, RegistryOps<JsonElement> ops, RecipeHolder<?> holder) {
        if (encodedFor != server.getRecipeManager()) {
            ENCODED.clear();
            encodedFor = server.getRecipeManager();
        }
        return ENCODED.computeIfAbsent(holder.id(), id -> java.util.Optional.ofNullable(encode(ops, holder.value()))).orElse(null);
    }

    private static JsonObject encode(RegistryOps<JsonElement> ops, Recipe<?> recipe) {
        try {
            return Recipe.CODEC.encodeStart(ops, recipe).result().filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject).orElse(null);
        } catch (RuntimeException e) {
            return null; // a recipe whose serializer cannot encode it still shows its items
        }
    }

    /** Create-style "results": [{"id"/"item": ..., "count": n, "chance": p}]. */
    static List<JsonObject> extraOutputs(JsonObject encoded) {
        List<JsonObject> out = new ArrayList<>();
        if (encoded == null || !encoded.has("results") || !encoded.get("results").isJsonArray()) {
            return out;
        }
        for (JsonElement element : encoded.getAsJsonArray("results")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject r = element.getAsJsonObject();
            String id = r.has("id") ? r.get("id").getAsString() : r.has("item") && r.get("item").isJsonPrimitive() ? r.get("item").getAsString() : null;
            if (id == null) {
                continue;
            }
            JsonObject extra = new JsonObject();
            extra.addProperty("item", id);
            extra.addProperty("count", r.has("count") ? r.get("count").getAsInt() : 1);
            if (r.has("chance")) {
                extra.addProperty("chance", r.get("chance").getAsDouble());
            }
            out.add(extra);
        }
        return out;
    }

    /** Any {"fluid": id, "amount": n} (or a fluid tag) in the encoded recipe, as "water 250 mB". */
    static void collectFluids(JsonElement element, JsonArray out, int depth) {
        if (element == null || depth > 8 || out.size() >= 8) {
            return;
        }
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(child -> collectFluids(child, out, depth + 1));
        } else if (element.isJsonObject()) {
            JsonObject o = element.getAsJsonObject();
            String fluid = o.has("fluid") && o.get("fluid").isJsonPrimitive() ? o.get("fluid").getAsString()
                    : o.has("fluid_tag") && o.get("fluid_tag").isJsonPrimitive() ? "#" + o.get("fluid_tag").getAsString() : null;
            if (fluid != null && o.has("amount")) {
                out.add(fluid + " " + o.get("amount").getAsInt() + " mB");
                return;
            }
            o.entrySet().forEach(e -> collectFluids(e.getValue(), out, depth + 1));
        }
    }

    private static Item item(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
        Registry<Item> items = BuiltInRegistries.ITEM;
        Item item = location == null ? null : items.getOptional(location).orElse(null);
        return item == null || item == net.minecraft.world.item.Items.AIR ? null : item;
    }

    private static String itemId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static String str(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    private static JsonObject error(String op, String message) {
        JsonObject out = new JsonObject();
        out.addProperty("op", op);
        out.addProperty("error", message);
        return out;
    }
}
