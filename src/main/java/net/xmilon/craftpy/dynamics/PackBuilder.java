package net.xmilon.craftpy.dynamics;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
            String projectId = holder.contains(":") ? holder.substring(0, holder.indexOf(':')) : "craftpy";
            String texture = def != null && def.texture != null ? mapRef(def.texture, projectId, null) : UNLOADED_TEXTURE;
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
            String projectId = holder.contains(":") ? holder.substring(0, holder.indexOf(':')) : "craftpy";
            String texture = def != null && def.texture != null ? mapRef(def.texture, projectId, null) : UNLOADED_TEXTURE;
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

    static void writeDataPackForTests(Path root) throws Exception {
        writeDataPack(root);
    }

    private static void writeDataPack(Path root) throws Exception {
        deleteRecursively(root);
        writeMcmeta(root, DATA_FORMAT, "CraftPy project data");

        for (String projectId : SlotRegistry.activeProjectIds()) {
            Path data = root.resolve("data").resolve(projectId);
            Declarations declarations = SlotRegistry.declarations(projectId);
            if (declarations == null) {
                continue;
            }
            java.util.Map<String, JsonObject> declaredLoot = new java.util.LinkedHashMap<>();
            for (Declarations.DatapackEntry entry : declarations.datapack()) {
                if (entry.payload() == null || !entry.payload().isJsonObject()) {
                    continue;
                }
                JsonObject json = entry.payload().getAsJsonObject();
                switch (entry.kind()) {
                    case "recipe" -> writeRecipe(data, projectId, json);
                    case "loot_table" -> {
                        writeLootTable(data, projectId, json);
                        String id = entry.id();
                        if (id.isBlank()) id = jsonString(json, "id", "");
                        if (!id.isBlank()) {
                            declaredLoot.put(id, json);
                        }
                    }
                    case "tag" -> writeTag(data, projectId, json);
                    default -> {
                    }
                }
            }
            // Also pick up loot defined inline on blocks
            for (Declarations.Content content : declarations.contents()) {
                if ("block".equals(content.kind()) && content.definition().has("loot")) {
                    JsonElement loot = content.definition().get("loot");
                    if (loot.isJsonArray()) {
                        JsonObject table = new JsonObject();
                        table.addProperty("id", content.id());
                        table.add("entries", loot.getAsJsonArray());
                        declaredLoot.put(content.id(), table);
                    }
                }
            }
            writeBlockLootTables(data, projectId, declaredLoot);
        }
    }

    /**
     * Every mapped block slot gets a loot table at the vanilla lookup path
     * ({@code loot_table/blocks/pcblock_<i>.json}) so mining a project block
     * behaves like a normal Minecraft block: it drops itself, unless the project
     * declared a {@code loot_table("<content_id>", ...)} with the same id as the
     * block - then that one is used instead.
     */
    private static void writeBlockLootTables(Path data, String projectId,
            java.util.Map<String, JsonObject> declaredLoot) throws Exception {
        for (java.util.Map.Entry<String, SlotRegistry.SlotRef> entry : SlotRegistry.snapshotByHolder().entrySet()) {
            if (entry.getValue().kind() != SlotRegistry.Kind.BLOCK
                    || !entry.getKey().startsWith(projectId + ":")) {
                continue;
            }
            String contentId = entry.getKey().substring(projectId.length() + 1);
            String target = "blocks/pcblock_" + entry.getValue().index();
            JsonObject custom = declaredLoot.get(contentId);
            if (custom != null && custom.has("entries") && custom.getAsJsonArray("entries").size() > 0) {
                writeLootTable(data, projectId, custom, target);
            } else {
                writeJson(selfDropLoot("craftpy:pcblock_" + entry.getValue().index()),
                        data.resolve("loot_table/" + target + ".json"));
            }
        }
    }

    /** A block-type loot table that simply drops the given item. Package-visible for tests. */
    static JsonObject selfDropLoot(String itemRef) {
        JsonObject entry = new JsonObject();
        entry.addProperty("type", "minecraft:item");
        entry.addProperty("name", itemRef);
        JsonArray entries = new JsonArray();
        entries.add(entry);
        JsonObject pool = new JsonObject();
        pool.addProperty("rolls", 1.0f);
        pool.add("entries", entries);
        JsonArray pools = new JsonArray();
        pools.add(pool);
        JsonObject table = new JsonObject();
        table.addProperty("type", "minecraft:block");
        table.add("pools", pools);
        return table;
    }

    static void writeRecipe(Path data, String projectId, JsonObject raw) throws Exception {
        String type = jsonString(raw, "type", "shapeless");
        JsonObject out = new JsonObject();
        String fallback = "unknown";
        switch (type) {
            case "shaped" -> {
                out.addProperty("type", "minecraft:crafting_shaped");
                out.add("pattern", raw.getAsJsonArray("pattern"));
                out.add("keys", rewriteKeys(raw.getAsJsonObject("keys"), projectId));
                out.add("result", resultObject(mapRef(jsonString(raw, "result", "minecraft:stick"), projectId, SlotRegistry.Kind.ITEM)));
                out.addProperty("category", jsonString(raw, "category", "misc"));
                fallback = jsonString(raw, "result", "result");
            }
            case "smelting" -> {
                JsonObject ingredient = new JsonObject();
                ingredient.addProperty("item", mapRef(jsonString(raw, "ingredient", "minecraft:iron_ore"), projectId, SlotRegistry.Kind.ITEM));
                out.addProperty("type", "minecraft:smelting");
                out.add("ingredient", ingredient);
                out.add("result", resultObject(mapRef(jsonString(raw, "result", "minecraft:iron_ingot"), projectId, SlotRegistry.Kind.ITEM)));
                out.addProperty("experience", jsonFloat(raw, "xp", 0.0f));
                out.addProperty("cookingtime", jsonInt(raw, "cook_time", 200));
                fallback = jsonString(raw, "ingredient", "result");
            }
            default -> {
                out.addProperty("type", "minecraft:crafting_shapeless");
                out.add("result", resultObject(mapRef(jsonString(raw, "result", "minecraft:stick"), projectId, SlotRegistry.Kind.ITEM)));
                out.addProperty("category", jsonString(raw, "category", "misc"));
                JsonArray ingredients = new JsonArray();
                JsonArray rawIngredients = raw.getAsJsonArray("ingredients");
                for (var element : rawIngredients) {
                    JsonObject item = new JsonObject();
                    item.addProperty("item", mapRef(element.getAsString(), projectId, SlotRegistry.Kind.ITEM));
                    ingredients.add(item);
                }
                out.add("ingredients", ingredients);
                fallback = jsonString(raw, "result", "result");
            }
        }
        String name = fileName(jsonString(raw, "id", fallback));
        writeJson(out, data.resolve("recipe/" + name + ".json"));
    }

    static JsonObject resultObject(String itemRef) {
        JsonObject result = new JsonObject();
        result.addProperty("id", itemRef);
        result.addProperty("count", 1);
        return result;
    }

    static JsonObject rewriteKeys(JsonObject keys, String projectId) {
        JsonObject out = new JsonObject();
        for (String key : keys.keySet()) {
            JsonObject ingredient = keys.getAsJsonObject(key);
            if (ingredient.has("item")) {
                ingredient.addProperty("item", mapRef(ingredient.get("item").getAsString(), projectId, SlotRegistry.Kind.ITEM));
            }
            out.add(key, ingredient);
        }
        return out;
    }

    static void writeLootTable(Path data, String projectId, JsonObject raw) throws Exception {
        writeLootTable(data, projectId, raw, fileName(jsonString(raw, "id", "loot")));
    }

    /** Writes a block-type loot table to {@code loot_table/<target>.json}. */
    static void writeLootTable(Path data, String projectId, JsonObject raw, String target) throws Exception {
        JsonObject table = new JsonObject();
        table.addProperty("type", "minecraft:block");
        JsonArray pools = new JsonArray();
        JsonObject pool = new JsonObject();
        pool.addProperty("rolls", 1.0f);
        JsonArray entries = new JsonArray();
        for (var element : raw.getAsJsonArray("entries")) {
            JsonObject entry = new JsonObject();
            entry.addProperty("type", "minecraft:item");
            entry.addProperty("name", mapRef(element.getAsString(), projectId, SlotRegistry.Kind.ITEM));
            entries.add(entry);
        }
        pool.add("entries", entries);
        pools.add(pool);
        table.add("pools", pools);
        writeJson(table, data.resolve("loot_table/" + target + ".json"));
    }

    static void writeTag(Path data, String projectId, JsonObject raw) throws Exception {
        JsonObject tag = new JsonObject();
        tag.addProperty("replace", jsonBool(raw, "replace", false));
        String type = jsonString(raw, "type", "item");
        SlotRegistry.Kind kind = type.equals("block") ? SlotRegistry.Kind.BLOCK : SlotRegistry.Kind.ITEM;
        JsonArray values = new JsonArray();
        for (var element : raw.getAsJsonArray("values")) {
            values.add(mapRef(element.getAsString(), projectId, kind));
        }
        tag.add("values", values);
        String id = fileName(jsonString(raw, "id", "example"));
        writeJson(tag, data.resolve("tags/" + type + "/" + id + ".json"));
    }

    static String mapRef(String ref, String buildProject) {
        return mapRef(ref, buildProject, null);
    }

    /** Maps "<projectId>:<contentId>" references to the allocated slot registry id. */
    static String mapRef(String ref, String buildProject, SlotRegistry.Kind preferredKind) {
        if (ref == null || ref.isBlank()) {
            return ref;
        }
        String val = ref;
        if (ref.startsWith("project:")) {
            val = ref.substring("project:".length());
            if (!val.contains(":")) {
                val = buildProject + ":" + val;
            }
        }
        String candidate = val.contains(":") ? val : buildProject + ":" + val;
        var mapping = SlotRegistry.snapshotByHolder();

        SlotRegistry.SlotRef slot = null;
        if (preferredKind != null) {
            slot = mapping.get(preferredKind.name() + ":" + candidate);
        }
        if (slot == null) {
            // Try common slot types; heuristic for ambiguous references
            slot = mapping.get("BLOCK:" + candidate);
            if (slot == null) {
                slot = mapping.get("ITEM:" + candidate);
            }
        }

        if (slot != null) {
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
        String out = rawId.replace(':', '_');
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