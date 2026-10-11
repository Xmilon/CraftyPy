package net.xmilon.craftpy.dynamics;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.xmilon.craftpy.CraftPy;
import net.xmilon.craftpy.content.Defs;
import net.xmilon.craftpy.project.PyForgePaths;
import net.xmilon.craftpy.python.Declarations;
import net.xmilon.craftpy.registry.SlotRegistry;
import net.xmilon.craftpy.util.Json;

/**
 * Generates the on-disk resource pack ({@code assets/}) and data pack
 * ({@code data/}) that expose loaded project content. The generated folders are
 * registered with the vanilla repository sources and rebuilt on every lifecycle
 * transition.
 */
public final class PackBuilder {
    private static final String UNLOADED_TEXTURE = "minecraft:block/magenta_wool";
    private static final String DIR_NAME = "craftpy-dynamic";
    private static final String RESOURCE_FORMAT = "34";
    private static final String DATA_FORMAT = "48";

    private PackBuilder() {
    }

    public static Path resourcePackRoot() {
        return PyForgePaths.root().resolve("build").resolve("resourcepack").resolve(DIR_NAME);
    }

    public static Path dataPackRoot() {
        return PyForgePaths.root().resolve("build").resolve("datapack").resolve(DIR_NAME);
    }

    /** Rebuilds both packs from the current SlotRegistry state. IO on worker thread. */
    public static synchronized void rebuild() {
        try {
            writeResourcePack(resourcePackRoot());
            writeDataPack(dataPackRoot());
            CraftPy.LOGGER.info("CraftPy dynamic packs rebuilt");
        } catch (Exception e) {
            CraftPy.LOGGER.error("Failed to rebuild dynamic packs", e);
        }
    }

    private static void writeResourcePack(Path root) throws Exception {
        deleteRecursively(root);
        Path assets = root.resolve("assets").resolve("craftpy");
        writeMcmeta(root, RESOURCE_FORMAT, "CraftPy project resources");

        JsonObject lang = new JsonObject();

        // Emit an asset for every registered slot so generic blocks/items always
        // resolve to a valid (placeholder when unloaded) model.
        for (int i = 0; i < SlotRegistry.BLOCK_CAP; i++) {
            Defs.BlockDef def = SlotRegistry.blockDef(i);
            String holder = SlotRegistry.holderForBlock(i);
            String texture = def != null && def.texture != null ? def.texture : UNLOADED_TEXTURE;
            String name = def != null && def.name != null ? def.name : "Unloaded: " + holder;
            String id = "pcblock_" + i;

            JsonObject state = new JsonObject();
            JsonObject variants = new JsonObject();
            for (int light = 0; light <= 15; light++) {
                JsonObject variant = new JsonObject();
                variant.addProperty("model", "craftpy:block/" + id);
                variants.add("craftpy_light=" + light, variant);
            }
            state.add("variants", variants);
            writeJson(state, assets.resolve("blockstates/" + id + ".json"));

            JsonObject model = new JsonObject();
            model.addProperty("parent", "minecraft:block/cube_all");
            JsonObject textures = new JsonObject();
            textures.addProperty("all", texture);
            model.add("textures", textures);
            writeJson(model, assets.resolve("models/block/" + id + ".json"));

            JsonObject itemModel = new JsonObject();
            itemModel.addProperty("parent", "craftpy:block/" + id);
            writeJson(itemModel, assets.resolve("models/item/" + id + ".json"));

            lang.addProperty("block.craftpy." + id, name);
        }

        for (int i = 0; i < SlotRegistry.ITEM_CAP; i++) {
            Defs.ItemDef def = SlotRegistry.itemDef(i);
            String holder = SlotRegistry.holderForItem(i);
            String texture = def != null && def.texture != null ? def.texture : UNLOADED_TEXTURE;
            String name = def != null && def.name != null ? def.name : "Unloaded: " + holder;
            String id = "pcitem_" + i;

            JsonObject itemModel = new JsonObject();
            itemModel.addProperty("parent", "minecraft:item/generated");
            JsonObject textures = new JsonObject();
            textures.addProperty("layer0", texture);
            itemModel.add("textures", textures);
            writeJson(itemModel, assets.resolve("models/item/" + id + ".json"));

            lang.addProperty("item.craftpy." + id, name);
        }

        writeJson(lang, assets.resolve("lang/en_us.json"));

        // Ship each loaded project's own assets/ folder into the pack under its
        // namespace (assets/<projectId>/...) so textures, models and sounds are
        // actually resolvable in-game and show up in the asset bank.
        copyProjectAssets(root);
    }

    private static void copyProjectAssets(Path root) {
        net.xmilon.craftpy.project.ProjectManager manager = net.xmilon.craftpy.project.ProjectManager.get();
        if (manager == null) {
            return;
        }
        Path projectsDir = manager.projectsDir();
        for (String projectId : SlotRegistry.activeProjectIds()) {
            Path source = projectsDir.resolve(projectId).resolve("assets");
            if (!Files.isDirectory(source)) {
                continue;
            }
            try {
                copyTree(source, root.resolve("assets").resolve(projectId));
            } catch (Exception e) {
                CraftPy.LOGGER.warn("Failed to copy assets of project {}", projectId, e);
            }
        }
    }

    private static void copyTree(Path source, Path target) throws Exception {
        try (java.util.stream.Stream<Path> stream = Files.walk(source)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                Path dest = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(path, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void writeDataPack(Path root) throws Exception {
        deleteRecursively(root);
        writeMcmeta(root, DATA_FORMAT, "CraftPy project data");
        Path data = root.resolve("data").resolve("craftpy");

        for (String projectId : SlotRegistry.activeProjectIds()) {
            Declarations declarations = SlotRegistry.declarations(projectId);
            if (declarations == null) {
                continue;
            }
            for (Declarations.DatapackEntry entry : declarations.datapack()) {
                if (entry.payload() == null || !entry.payload().isJsonObject()) {
                    continue;
                }
                JsonObject json = entry.payload().getAsJsonObject();
                switch (entry.kind()) {
                    case "recipe" -> writeRecipe(data, projectId, json);
                    case "loot_table" -> writeLootTable(data, projectId, json);
                    case "tag" -> writeTag(data, projectId, json);
                    default -> {
                    }
                }
            }
        }
    }

    private static void writeRecipe(Path data, String projectId, JsonObject raw) throws Exception {
        String type = jsonString(raw, "type", "shapeless");
        JsonObject out = new JsonObject();
        switch (type) {
            case "shaped" -> {
                out.addProperty("type", "minecraft:crafting_shaped");
                out.add("pattern", raw.getAsJsonArray("pattern"));
                out.add("keys", rewriteKeys(raw.getAsJsonObject("keys"), projectId));
                out.add("result", resultObject(mapRef(jsonString(raw, "result", "minecraft:stick"), projectId)));
                out.addProperty("category", jsonString(raw, "category", "misc"));
            }
            case "smelting" -> {
                JsonObject ingredient = new JsonObject();
                ingredient.addProperty("item", mapRef(jsonString(raw, "ingredient", "minecraft:iron_ore"), projectId));
                out.addProperty("type", "minecraft:smelting");
                out.add("ingredient", ingredient);
                out.add("result", resultObject(mapRef(jsonString(raw, "result", "minecraft:iron_ingot"), projectId)));
                out.addProperty("experience", jsonFloat(raw, "xp", 0.0f));
                out.addProperty("cookingtime", jsonInt(raw, "cook_time", 200));
            }
            default -> {
                out.addProperty("type", "minecraft:crafting_shapeless");
                out.add("result", resultObject(mapRef(jsonString(raw, "result", "minecraft:stick"), projectId)));
                out.addProperty("category", jsonString(raw, "category", "misc"));
                JsonArray ingredients = new JsonArray();
                JsonArray rawIngredients = raw.getAsJsonArray("ingredients");
                for (var element : rawIngredients) {
                    JsonObject item = new JsonObject();
                    item.addProperty("item", mapRef(element.getAsString(), projectId));
                    ingredients.add(item);
                }
                out.add("ingredients", ingredients);
            }
        }
        String name = fileName(jsonString(raw, "id", jsonString(raw, "result", "result")));
        writeJson(out, data.resolve("recipe/" + name + ".json"));
    }

    private static JsonObject resultObject(String itemRef) {
        JsonObject result = new JsonObject();
        result.addProperty("id", itemRef);
        result.addProperty("count", 1);
        return result;
    }

    private static JsonObject rewriteKeys(JsonObject keys, String projectId) {
        JsonObject out = new JsonObject();
        for (String key : keys.keySet()) {
            JsonObject ingredient = keys.getAsJsonObject(key);
            if (ingredient.has("item")) {
                ingredient.addProperty("item", mapRef(ingredient.get("item").getAsString(), projectId));
            }
            out.add(key, ingredient);
        }
        return out;
    }

    private static void writeLootTable(Path data, String projectId, JsonObject raw) throws Exception {
        JsonObject table = new JsonObject();
        table.addProperty("type", "minecraft:block");
        JsonArray pools = new JsonArray();
        JsonObject pool = new JsonObject();
        pool.addProperty("rolls", 1.0f);
        JsonArray entries = new JsonArray();
        for (var element : raw.getAsJsonArray("entries")) {
            JsonObject entry = new JsonObject();
            entry.addProperty("type", "minecraft:item");
            entry.addProperty("name", mapRef(element.getAsString(), projectId));
            entries.add(entry);
        }
        pool.add("entries", entries);
        pools.add(pool);
        table.add("pools", pools);
        String name = fileName(jsonString(raw, "id", "loot"));
        writeJson(table, data.resolve("loot_table/" + name + ".json"));
    }

    private static void writeTag(Path data, String projectId, JsonObject raw) throws Exception {
        JsonObject tag = new JsonObject();
        tag.addProperty("replace", jsonBool(raw, "replace", false));
        JsonArray values = new JsonArray();
        for (var element : raw.getAsJsonArray("values")) {
            values.add(mapRef(element.getAsString(), projectId));
        }
        tag.add("values", values);
        String type = jsonString(raw, "type", "item");
        String id = fileName(jsonString(raw, "id", "example"));
        writeJson(tag, data.resolve("tags/" + type + "/" + id + ".json"));
    }

    /** Maps "<projectId>:<contentId>" references to the allocated slot registry id. */
    static String mapRef(String ref, String buildProject) {
        if (ref == null || ref.isBlank()) {
            return ref;
        }
        String candidate = ref.contains(":") ? ref : buildProject + ":" + ref;
        var mapping = SlotRegistry.snapshotByHolder();
        if (mapping.containsKey(candidate)) {
            var slot = mapping.get(candidate);
            if (slot.kind() == SlotRegistry.Kind.BLOCK) {
                return "craftpy:pcblock_" + slot.index();
            }
            if (slot.kind() == SlotRegistry.Kind.ITEM) {
                return "craftpy:pcitem_" + slot.index();
            }
        }
        return ref;
    }

    private static String fileName(String rawId) {
        String out = rawId.contains(":") ? rawId.substring(rawId.indexOf(':') + 1) : rawId;
        return out.replaceAll("[^a-z0-9_./-]", "_");
    }

    private static String jsonString(JsonObject json, String key, String fallback) {
        var element = json.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : fallback;
    }

    private static int jsonInt(JsonObject json, String key, int fallback) {
        var element = json.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
                ? element.getAsInt() : fallback;
    }

    private static float jsonFloat(JsonObject json, String key, float fallback) {
        var element = json.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
                ? element.getAsFloat() : fallback;
    }

    private static boolean jsonBool(JsonObject json, String key, boolean fallback) {
        var element = json.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()
                ? element.getAsBoolean() : fallback;
    }

    private static void writeMcmeta(Path root, String format, String description) throws Exception {
        JsonObject meta = new JsonObject();
        meta.addProperty("pack_format", Integer.parseInt(format));
        JsonObject desc = new JsonObject();
        desc.addProperty("text", description);
        meta.add("description", desc);
        JsonObject pack = new JsonObject();
        pack.add("pack", meta);
        Files.createDirectories(root);
        writeString(root.resolve("pack.mcmeta"), Json.GSON.toJson(pack));
    }

    private static void writeJson(JsonObject json, Path target) throws Exception {
        if (json.entrySet().isEmpty()) {
            return;
        }
        Files.createDirectories(target.getParent());
        writeString(target, Json.GSON.toJson(json));
    }

    private static void writeString(Path target, String content) throws Exception {
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path dir) throws java.io.IOException {
        net.xmilon.craftpy.project.ProjectManager.deleteRecursively(dir);
    }
}