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
        return PyForgePaths.build().resolve("resourcepack").resolve(DIR_NAME);
    }

    public static Path dataPackRoot() {
        return PyForgePaths.build().resolve("datapack").resolve(DIR_NAME);
    }

    /** Rebuilds both packs from the current SlotRegistry state. IO on worker thread. */
    /**
     * Writes both packs into a staging folder and then swaps them in. A resource reload that runs
     * meanwhile (several projects/modules load at world start) therefore never sees a half-written
     * pack - that used to show up as "missing model for variant" and purple blocks.
     */
    public static synchronized void rebuild() {
        try {
            Path staging = PyForgePaths.build().resolve("staging");
            Path resources = staging.resolve("resourcepack");
            Path data = staging.resolve("datapack");
            writeResourcePack(resources);
            writeDataPack(data);
            swapIn(resources, resourcePackRoot());
            swapIn(data, dataPackRoot());
            CraftPy.LOGGER.info("CraftPy dynamic packs rebuilt");
        } catch (Exception e) {
            CraftPy.LOGGER.error("Failed to rebuild dynamic packs", e);
        }
    }

    /** Replaces {@code target} with {@code built} using two renames (retried: Windows locks files being read). */
    private static void swapIn(Path built, Path target) throws Exception {
        Path old = built.resolveSibling(built.getFileName() + ".old");
        deleteRecursively(old);
        Files.createDirectories(target.getParent());
        Exception last = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                if (Files.exists(target)) {
                    Files.move(target, old);
                }
                Files.move(built, target);
                deleteRecursively(old);
                return;
            } catch (java.io.IOException e) {
                last = e;
                if (!Files.exists(target) && Files.exists(old)) {
                    // Put the previous pack back before retrying so it never disappears.
                    try {
                        Files.move(old, target);
                    } catch (java.io.IOException ignored) {
                        // retried below
                    }
                }
                Thread.sleep(50);
            }
        }
        throw last;
    }

    private static void writeResourcePack(Path root) throws Exception {
        deleteRecursively(root);
        Path assets = root.resolve("assets").resolve("craftpy");
        writeMcmeta(root, RESOURCE_FORMAT, "CraftPy project resources");

        JsonObject lang = new JsonObject();

        // Emit an asset for every registered slot so generic blocks/items always
        // resolve to a valid (placeholder when unloaded) model.
        for (int i = 0; i < SlotRegistry.blockSlotCount(); i++) {
            Defs.BlockDef def = SlotRegistry.blockDef(i);
            String holder = SlotRegistry.holderForBlock(i);
            String projectId = ownerOf(holder);
            String texture = def != null && def.texture != null
                    ? texturePath(mapRef(def.texture, projectId, null), projectId, "block") : UNLOADED_TEXTURE;
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

        for (int i = 0; i < SlotRegistry.itemSlotCount(); i++) {
            Defs.ItemDef def = SlotRegistry.itemDef(i);
            String holder = SlotRegistry.holderForItem(i);
            String projectId = ownerOf(holder);
            String texture = def != null && def.texture != null
                    ? texturePath(mapRef(def.texture, projectId, null), projectId, "item") : UNLOADED_TEXTURE;
            String name = def != null && def.name != null ? def.name : "Unloaded: " + holder;
            String id = "pcitem_" + i;

            JsonObject itemModel = new JsonObject();
            // tools and weapons are held like vanilla ones (handheld); everything else is a flat sprite
            itemModel.addProperty("parent", def != null && (def.tool != null || def.weapon != null)
                    ? "minecraft:item/handheld" : "minecraft:item/generated");
            JsonObject textures = new JsonObject();
            textures.addProperty("layer0", texture);
            itemModel.add("textures", textures);
            writeJson(itemModel, assets.resolve("models/item/" + id + ".json"));

            lang.addProperty("item.craftpy." + id, name);
        }

        // Merge translations declared from Python via lang("key", "value").
        for (String projectId : SlotRegistry.activeProjectIds()) {
            Declarations declarations = SlotRegistry.declarations(projectId);
            if (declarations == null) {
                continue;
            }
            for (Declarations.DatapackEntry entry : declarations.datapack()) {
                if ("lang".equals(entry.kind()) && entry.payload() != null && entry.payload().isJsonObject()) {
                    for (var field : entry.payload().getAsJsonObject().entrySet()) {
                        lang.add(field.getKey(), field.getValue());
                    }
                }
            }
        }

        writeJson(lang, assets.resolve("lang/en_us.json"));

        // Ship each loaded project's own assets/ folder into the pack under its
        // namespace (assets/<projectId>/...) so textures, models and sounds are
        // actually resolvable in-game and show up in the asset bank.
        copyProjectAssets(root);
        writeProjectResourceExtras(root);
    }

    /** Writes resource-pack assets described from Python (sounds.json, particles). */
    private static void writeProjectResourceExtras(Path root) throws Exception {
        for (String projectId : SlotRegistry.activeProjectIds()) {
            Declarations declarations = SlotRegistry.declarations(projectId);
            if (declarations == null) {
                continue;
            }
            JsonObject sounds = new JsonObject();
            Path namespace = root.resolve("assets").resolve(projectId);
            for (Declarations.DatapackEntry entry : declarations.datapack()) {
                JsonObject payload = entry.payload() != null && entry.payload().isJsonObject()
                        ? entry.payload().getAsJsonObject() : null;
                if (payload == null) {
                    continue;
                }
                JsonElement definition = payload.get("definition");
                switch (entry.kind()) {
                    case "sound" -> {
                        if (definition != null && definition.isJsonObject() && !entry.id().isBlank()) {
                            sounds.add(entry.id(), definition);
                        }
                    }
                    case "particle" -> {
                        if (definition != null && definition.isJsonObject() && !entry.id().isBlank()) {
                            writeJson(definition.getAsJsonObject(),
                                    namespace.resolve("particles/" + fileName(entry.id()) + ".json"));
                        }
                    }
                    default -> {
                    }
                }
            }
            if (!sounds.entrySet().isEmpty()) {
                writeJson(mergeSounds(namespace.resolve("sounds.json"), sounds), namespace.resolve("sounds.json"));
            }
        }
    }

    /**
     * The project may ship its own {@code sounds.json} (copied into the pack before this runs); sounds declared from Python
     * are added to it and win on the same name, instead of replacing the whole file.
     */
    static JsonObject mergeSounds(Path existingFile, JsonObject declared) {
        JsonObject merged = new JsonObject();
        if (Files.isRegularFile(existingFile)) {
            try {
                JsonElement existing = com.google.gson.JsonParser.parseString(Files.readString(existingFile));
                if (existing.isJsonObject()) {
                    merged = existing.getAsJsonObject();
                }
            } catch (Exception e) {
                CraftPy.LOGGER.warn("Ignoring unreadable {}: {}", existingFile, e.toString());
            }
        }
        for (var entry : declared.entrySet()) {
            merged.add(entry.getKey(), entry.getValue());
        }
        return merged;
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
            try (java.util.stream.Stream<Path> children = Files.list(source)) {
                for (Path child : (Iterable<Path>) children::iterator) {
                    String name = child.getFileName().toString();
                    String namespace = assetNamespace(projectId, name, Files.isDirectory(child));
                    if (namespace == null) {
                        CraftPy.LOGGER.warn("Project {}: ignoring assets/{} (not a valid namespace folder)", projectId, name);
                        continue;
                    }
                    Path target = root.resolve("assets").resolve(namespace);
                    if (Files.isDirectory(child)) {
                        // a namespace folder is the namespace itself; a resource folder goes inside the project's namespace
                        copyTree(child, RESOURCE_FOLDERS.contains(name) ? target.resolve(name) : target);
                    } else {
                        Files.createDirectories(target);
                        Files.copy(child, target.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } catch (Exception e) {
                CraftPy.LOGGER.warn("Failed to copy assets of project {}", projectId, e);
            }
        }
    }

    /** Folders of a resource pack namespace; directly under {@code assets/} they belong to the project's own namespace. */
    private static final java.util.Set<String> RESOURCE_FOLDERS = java.util.Set.of(
            "textures", "models", "blockstates", "sounds", "lang", "font", "particles", "atlases", "shaders", "texts",
            "items", "equipment", "post_effect", "gpu_warnlist", "regional_compliancies");

    /**
     * Where an entry directly under a project's {@code assets/} goes. {@code textures/}, {@code models/}, {@code lang/}...
     * and loose files such as {@code sounds.json} belong to the project's namespace (the simple way); any other folder is a
     * namespace of its own, like in a mod or resource pack: {@code assets/minecraft/textures/block/stone.png} retextures
     * vanilla. Null for a folder name that cannot be a namespace.
     */
    static String assetNamespace(String projectId, String entryName, boolean directory) {
        if (!directory || RESOURCE_FOLDERS.contains(entryName)) {
            return projectId;
        }
        return entryName.matches("[a-z0-9_.-]+") ? entryName : null;
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
        writeToolTags(root);

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
                    case "advancement" -> writeDefinitionDatapack(data, "advancement", entry);
                    case "enchantment" -> writeDefinitionDatapack(data, "enchantment", entry);
                    case "raw" -> writeRawFile(data, entry);
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
            // Keys are "BLOCK:<project>:<id>" (older saves: "<project>:<id>").
            String key = entry.getKey().startsWith("BLOCK:") ? entry.getKey().substring("BLOCK:".length()) : entry.getKey();
            if (entry.getValue().kind() != SlotRegistry.Kind.BLOCK || !key.startsWith(projectId + ":")) {
                continue;
            }
            String contentId = key.substring(projectId.length() + 1);
            String target = "blocks/pcblock_" + entry.getValue().index();
            JsonObject custom = declaredLoot.get(contentId);
            // A declared table wins, even an empty one (drops=[] means "drops nothing").
            JsonObject table = custom != null ? buildLootTable(projectId, custom, target)
                    : selfDropLoot("craftpy:pcblock_" + entry.getValue().index());
            Defs.BlockDef def = SlotRegistry.blockDef(entry.getValue().index());
            if (def != null && def.needsTool) {
                requireTool(table, def.tool, def.minLevel);
            }
            writeJson(table, data.resolve("loot_table/" + target + ".json"));
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
        if (type.contains(":")) { // another mod's recipe type, for example create:mixing
            String name = fileName(jsonString(raw, "id", type.replace(':', '_') + "_" + Integer.toHexString(raw.toString().hashCode())));
            writeJson(modRecipe(raw, projectId), data.resolve("recipe/" + name + ".json"));
            return;
        }
        if (!BUILT_IN_RECIPES.contains(type)) { // it used to become a shapeless recipe, which is never what was meant
            CraftPy.LOGGER.warn("Project {}: recipe type '{}' is unknown and was skipped (use recipe(\"mod:type\", ...) for other mods)", projectId, type);
            return;
        }
        JsonObject out = new JsonObject();
        String fallback = "unknown";
        switch (type) {
            case "shaped" -> {
                out.addProperty("type", "minecraft:crafting_shaped");
                out.add("pattern", raw.getAsJsonArray("pattern"));
                // 1.21 shaped recipes use "key"; scripts may send "keys" (older API) or "key".
                JsonObject keys = raw.has("key") && raw.get("key").isJsonObject() ? raw.getAsJsonObject("key")
                        : raw.has("keys") && raw.get("keys").isJsonObject() ? raw.getAsJsonObject("keys") : null;
                out.add("key", rewriteKeys(keys, projectId));
                out.add("result", resultObject(mapRef(jsonString(raw, "result", "minecraft:stick"), projectId, SlotRegistry.Kind.ITEM),
                        jsonInt(raw, "count", 1)));
                out.addProperty("category", jsonString(raw, "category", "misc"));
                fallback = jsonString(raw, "result", "result");
            }
            case "smelting", "blasting", "smoking", "campfire" -> {
                String vanilla = switch (type) {
                    case "blasting" -> "minecraft:blasting";
                    case "smoking" -> "minecraft:smoking";
                    case "campfire" -> "minecraft:campfire_cooking";
                    default -> "minecraft:smelting";
                };
                out.addProperty("type", vanilla);
                out.add("ingredient", ingredient(raw.get("ingredient"), "minecraft:iron_ore", projectId));
                out.add("result", resultObject(mapRef(jsonString(raw, "result", "minecraft:iron_ingot"), projectId, SlotRegistry.Kind.ITEM),
                        jsonInt(raw, "count", 1)));
                out.addProperty("experience", jsonFloat(raw, "xp", 0.0f));
                out.addProperty("cookingtime", jsonInt(raw, "cook_time", 200));
                fallback = refName(raw.get("ingredient"), "result");
            }
            case "stonecutting" -> {
                out.addProperty("type", "minecraft:stonecutting");
                out.add("ingredient", ingredient(raw.get("ingredient"), "minecraft:stone", projectId));
                out.add("result", resultObject(mapRef(jsonString(raw, "result", "minecraft:stone"), projectId, SlotRegistry.Kind.ITEM),
                        jsonInt(raw, "count", 1)));
                fallback = jsonString(raw, "result", "result");
            }
            case "smithing" -> {
                out.addProperty("type", "minecraft:smithing_transform");
                out.add("template", ingredient(raw.get("template"), "minecraft:netherite_upgrade_smithing_template", projectId));
                out.add("base", ingredient(raw.get("base"), "minecraft:diamond_sword", projectId));
                out.add("addition", ingredient(raw.get("addition"), "minecraft:netherite_ingot", projectId));
                out.add("result", resultObject(mapRef(jsonString(raw, "result", "minecraft:netherite_sword"), projectId, SlotRegistry.Kind.ITEM),
                        jsonInt(raw, "count", 1)));
                fallback = jsonString(raw, "result", "result");
            }
            default -> {
                out.addProperty("type", "minecraft:crafting_shapeless");
                out.add("result", resultObject(mapRef(jsonString(raw, "result", "minecraft:stick"), projectId, SlotRegistry.Kind.ITEM),
                        jsonInt(raw, "count", 1)));
                out.addProperty("category", jsonString(raw, "category", "misc"));
                JsonArray ingredients = new JsonArray();
                JsonArray rawIngredients = raw.getAsJsonArray("ingredients");
                if (rawIngredients != null) {
                    for (var element : rawIngredients) {
                        ingredients.add(ingredient(element, "minecraft:stick", projectId));
                    }
                }
                out.add("ingredients", ingredients);
                fallback = jsonString(raw, "result", "result");
            }
        }
        String name = fileName(jsonString(raw, "id", fallback));
        writeJson(out, data.resolve("recipe/" + name + ".json"));
    }

    private static final java.util.Set<String> BUILT_IN_RECIPES = java.util.Set.of("shaped", "shapeless", "smelting", "blasting",
            "smoking", "campfire", "stonecutting", "smithing");

    /**
     * A recipe of another mod's type: the fields are written as given (the mod defines the layout), the project's own content
     * is mapped to its slots, and a mod_loaded condition keeps the pack loading when the mod is not installed.
     */
    static JsonObject modRecipe(JsonObject raw, String projectId) {
        String type = jsonString(raw, "type", "");
        JsonObject out = new JsonObject();
        out.addProperty("type", type);
        for (var entry : raw.entrySet()) {
            if (!entry.getKey().equals("type") && !entry.getKey().equals("id")) {
                out.add(entry.getKey(), mapContentRefs(entry.getValue(), projectId));
            }
        }
        String mod = type.substring(0, type.indexOf(':'));
        if (!mod.equals("minecraft") && !out.has("neoforge:conditions")) {
            JsonObject condition = new JsonObject();
            condition.addProperty("type", "neoforge:mod_loaded");
            condition.addProperty("modid", mod);
            JsonArray conditions = new JsonArray();
            conditions.add(condition);
            out.add("neoforge:conditions", conditions);
        }
        return out;
    }

    /** Strings under item, id and result keys that name project content become the slot ids; everything else is kept. */
    static JsonElement mapContentRefs(JsonElement value, String projectId) {
        if (value.isJsonArray()) {
            JsonArray out = new JsonArray();
            value.getAsJsonArray().forEach(element -> out.add(mapContentRefs(element, projectId)));
            return out;
        }
        if (value.isJsonObject()) {
            JsonObject out = new JsonObject();
            for (var entry : value.getAsJsonObject().entrySet()) {
                String key = entry.getKey();
                JsonElement child = entry.getValue();
                boolean ref = key.equals("item") || key.equals("id") || key.equals("result");
                out.add(key, ref && child.isJsonPrimitive() && child.getAsJsonPrimitive().isString()
                        ? new com.google.gson.JsonPrimitive(mapRef(child.getAsString(), projectId, SlotRegistry.Kind.ITEM))
                        : mapContentRefs(child, projectId));
            }
            return out;
        }
        return value;
    }

    /** A readable name for an ingredient value (string, {"item"} or {"tag"}), used for file names. */
    static String refName(JsonElement value, String fallback) {
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            for (String key : new String[] { "item", "tag" }) {
                if (object.has(key) && object.get(key).isJsonPrimitive()) {
                    return object.get(key).getAsString();
                }
            }
            return fallback;
        }
        return value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    /** Ingredient from a script value: {@code "id"}, {@code "#tag"}, {@code {"item": ..}} or {@code {"tag": ..}}. */
    static JsonObject ingredient(JsonElement value, String fallback, String projectId) {
        if (value == null || value.isJsonNull()) {
            return ingredient(fallback, projectId);
        }
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (object.has("tag")) {
                JsonObject tag = new JsonObject();
                String id = object.get("tag").getAsString();
                tag.addProperty("tag", id.startsWith("#") ? id.substring(1) : id);
                return tag;
            }
            if (object.has("item")) {
                return ingredient(object.get("item").getAsString(), projectId);
            }
            return ingredient(fallback, projectId);
        }
        return ingredient(value.isJsonPrimitive() ? value.getAsString() : fallback, projectId);
    }

    /** Builds an ingredient object; a leading {@code #} denotes a tag reference. */
    static JsonObject ingredient(String ref, String projectId) {
        JsonObject ingredient = new JsonObject();
        if (ref != null && ref.startsWith("#")) {
            ingredient.addProperty("tag", ref.substring(1));
        } else {
            ingredient.addProperty("item", mapRef(ref, projectId, SlotRegistry.Kind.ITEM));
        }
        return ingredient;
    }

    static JsonObject resultObject(String itemRef) {
        return resultObject(itemRef, 1);
    }

    static JsonObject resultObject(String itemRef, int count) {
        if (itemRef != null && itemRef.startsWith("#")) {
            JsonObject result = new JsonObject();
            result.addProperty("id", itemRef.substring(1));
            result.addProperty("count", Math.max(1, count));
            return result;
        }
        JsonObject result = new JsonObject();
        result.addProperty("id", itemRef);
        result.addProperty("count", Math.max(1, count));
        return result;
    }

    static JsonObject rewriteKeys(JsonObject keys, String projectId) {
        JsonObject out = new JsonObject();
        if (keys == null) {
            return out;
        }
        for (String key : keys.keySet()) {
            JsonElement value = keys.get(key);
            if (!value.isJsonObject()) {
                out.add(key, ingredient(value, "minecraft:stick", projectId));
                continue;
            }
            JsonObject ingredient = value.getAsJsonObject();
            if (ingredient.has("item")) {
                String ref = ingredient.get("item").getAsString();
                if (ref.startsWith("#")) {
                    ingredient.remove("item");
                    ingredient.addProperty("tag", ref.substring(1));
                } else {
                    ingredient.addProperty("item", mapRef(ref, projectId, SlotRegistry.Kind.ITEM));
                }
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
        writeJson(buildLootTable(projectId, raw, target), data.resolve("loot_table/" + target + ".json"));
    }

    private static final String[] TOOL_KINDS = {"pickaxe", "axe", "shovel", "hoe", "sword"};
    /** Vanilla tiers by mining level 1-4; wood and gold are level 0 and never needed for a minimum level. */
    private static final String[][] VANILLA_BY_LEVEL = {
            {"stone", "iron", "diamond", "netherite"}, {"iron", "diamond", "netherite"}, {"diamond", "netherite"},
            {"netherite"}};

    /**
     * Tool tags with the items that have a Tool extension: {@code minecraft:pickaxes} (and axes, shovels, hoes, swords)
     * so vanilla rules see them as tools, and {@code craftpy:level/<kind>/<1-4>} (tools of at least that mining level)
     * that the loot tables of blocks with a minimum tier ask for.
     */
    static void writeToolTags(Path root) throws Exception {
        for (String kind : TOOL_KINDS) {
            JsonArray any = new JsonArray();
            for (int i = 0; i < SlotRegistry.itemSlotCount(); i++) {
                Defs.ItemDef def = SlotRegistry.itemDef(i);
                if (def != null && def.tool != null && def.tool.kind().equals(kind)) {
                    any.add("craftpy:pcitem_" + i);
                }
            }
            if (!any.isEmpty()) {
                writeTag(root.resolve("data/minecraft/tags/item/" + kind + "s.json"), any);
            }
            for (int level = 1; level <= 4; level++) {
                JsonArray values = new JsonArray();
                for (String tier : VANILLA_BY_LEVEL[level - 1]) {
                    values.add("minecraft:" + tier + "_" + kind);
                }
                for (int i = 0; i < SlotRegistry.itemSlotCount(); i++) {
                    Defs.ItemDef def = SlotRegistry.itemDef(i);
                    if (def != null && def.tool != null && def.tool.kind().equals(kind) && def.tool.level() >= level) {
                        values.add("craftpy:pcitem_" + i);
                    }
                }
                writeTag(root.resolve("data/craftpy/tags/item/level/" + kind + "/" + level + ".json"), values);
            }
        }
    }

    private static void writeTag(Path file, JsonArray values) throws Exception {
        JsonObject tag = new JsonObject();
        tag.addProperty("replace", false);
        tag.add("values", values);
        writeJson(tag, file);
    }

    /** needs_tool: every pool only drops when the block is mined with the declared tool (like vanilla ores). */
    static void requireTool(JsonObject table, String tool) {
        requireTool(table, tool, 0);
    }

    /** As above; {@code minLevel > 0} also asks for that mining level (tags from {@link #writeToolTags}). */
    static void requireTool(JsonObject table, String tool, int minLevel) {
        String kind = tool == null ? "" : tool.toLowerCase(java.util.Locale.ROOT);
        String tag = switch (kind) {
            case "pickaxe" -> "#minecraft:pickaxes";
            case "axe" -> "#minecraft:axes";
            case "shovel" -> "#minecraft:shovels";
            case "hoe" -> "#minecraft:hoes";
            case "sword" -> "#minecraft:swords";
            default -> null;
        };
        if (tag != null && minLevel > 0) {
            tag = "#craftpy:level/" + kind + "/" + Math.min(4, minLevel);
        }
        if (tag == null || !table.has("pools")) {
            return;
        }
        JsonObject predicate = new JsonObject();
        predicate.addProperty("items", tag);
        JsonObject condition = new JsonObject();
        condition.addProperty("condition", "minecraft:match_tool");
        condition.add("predicate", predicate);
        for (var pool : table.getAsJsonArray("pools")) {
            JsonArray conditions = pool.getAsJsonObject().has("conditions")
                    ? pool.getAsJsonObject().getAsJsonArray("conditions") : new JsonArray();
            conditions.add(condition.deepCopy());
            pool.getAsJsonObject().add("conditions", conditions);
        }
    }

    static JsonObject buildLootTable(String projectId, JsonObject raw, String target) {
        JsonObject table = new JsonObject();
        table.addProperty("type", "minecraft:block");
        JsonArray pools = new JsonArray();
        JsonObject pool = new JsonObject();
        pool.addProperty("rolls", 1.0f);
        JsonArray entries = new JsonArray();
        // loot_self() / "self": the block drops itself. Block tables live at blocks/pcblock_<n>.
        String selfRef = target.startsWith("blocks/") ? "craftpy:" + target.substring("blocks/".length()) : null;
        JsonArray rawEntries = raw.has("entries") && raw.get("entries").isJsonArray()
                ? raw.getAsJsonArray("entries") : new JsonArray();
        for (var element : rawEntries) {
            JsonObject entry = new JsonObject();
            entry.addProperty("type", "minecraft:item");
            boolean self = element.isJsonObject() && "self".equals(jsonString(element.getAsJsonObject(), "value", ""))
                    || element.isJsonPrimitive() && "self".equals(element.getAsString());
            if (self) {
                if (selfRef == null) {
                    continue;
                }
                entry.addProperty("name", selfRef);
            } else if (element.isJsonObject() && element.getAsJsonObject().has("item")) {
                JsonObject spec = element.getAsJsonObject();
                entry.addProperty("name", mapRef(spec.get("item").getAsString(), projectId, SlotRegistry.Kind.ITEM));
                if (spec.has("weight")) {
                    entry.addProperty("weight", Math.max(1, spec.get("weight").getAsInt()));
                }
                if (spec.has("count")) {
                    entry.add("functions", countFunction(spec.get("count").getAsInt()));
                }
            } else if (element.isJsonPrimitive()) {
                entry.addProperty("name", mapRef(element.getAsString(), projectId, SlotRegistry.Kind.ITEM));
            } else {
                continue;
            }
            entries.add(entry);
        }
        pool.add("entries", entries);
        pools.add(pool);
        table.add("pools", pools);
        return table;
    }

    static void writeTag(Path data, String projectId, JsonObject raw) throws Exception {
        JsonObject tag = new JsonObject();
        tag.addProperty("replace", jsonBool(raw, "replace", false));
        String type = jsonString(raw, "type", "item");
        SlotRegistry.Kind kind = switch (type) {
            case "block", "blocks" -> SlotRegistry.Kind.BLOCK;
            case "item", "items" -> SlotRegistry.Kind.ITEM;
            default -> null;
        };
        JsonArray values = new JsonArray();
        for (var element : raw.getAsJsonArray("values")) {
            if (element.isJsonObject() && element.getAsJsonObject().has("id")) {
                JsonObject value = element.getAsJsonObject().deepCopy();
                String ref = value.get("id").getAsString();
                if (ref.startsWith("#")) {
                    value.addProperty("id", ref.substring(1));
                } else if (kind != null) {
                    value.addProperty("id", mapRef(ref, projectId, kind));
                }
                values.add(value);
            } else {
                String ref = element.getAsString();
                if (ref.startsWith("#") || kind == null) {
                    values.add(ref);
                } else {
                    values.add(mapRef(ref, projectId, kind));
                }
            }
        }
        tag.add("values", values);
        String id = fileName(jsonString(raw, "id", "example"));
        writeJson(tag, data.resolve("tags/" + tagFolder(type) + "/" + id + ".json"));
    }

    private static JsonArray countFunction(int count) {
        JsonObject fn = new JsonObject();
        fn.addProperty("function", "minecraft:set_count");
        fn.addProperty("count", Math.max(1, count));
        JsonArray functions = new JsonArray();
        functions.add(fn);
        return functions;
    }

    private static String tagFolder(String type) {
        String clean = type.contains(":") ? type.substring(type.indexOf(':') + 1) : type;
        return switch (clean) {
            case "item", "items" -> "items";
            case "block", "blocks" -> "blocks";
            case "entity_type", "entity_types" -> "entity_types";
            case "fluid", "fluids" -> "fluids";
            case "game_event", "game_events" -> "game_events";
            case "function", "functions" -> "functions";
            default -> clean.endsWith("s") ? clean : clean + "s";
        };
    }

    /** Writes the {@code definition} of a datapack entry to {@code data/<dir>/<id>.json}. */
    static void writeDefinitionDatapack(Path data, String dir, Declarations.DatapackEntry entry) throws Exception {
        JsonElement definition = entry.payload().getAsJsonObject().get("definition");
        if (definition == null || !definition.isJsonObject() || entry.id().isBlank()) {
            return;
        }
        writeJson(definition.getAsJsonObject(), data.resolve(dir + "/" + fileName(entry.id()) + ".json"));
    }

    /** Writes a raw user-supplied datapack file ({@code raw_json("path", data)}). */
    static void writeRawFile(Path data, Declarations.DatapackEntry entry) throws Exception {
        JsonElement definition = entry.payload().getAsJsonObject().get("definition");
        if (definition == null || entry.id().isBlank()) {
            return;
        }
        String path = entry.id();
        if (!path.endsWith(".json")) {
            path = path + ".json";
        }
        // namespace="minecraft" (or another mod) writes next to the project's own data folder: data/<namespace>/<path>
        JsonElement namespace = entry.payload().getAsJsonObject().get("namespace");
        Path base = data;
        if (namespace != null && namespace.isJsonPrimitive()) {
            String name = namespace.getAsString();
            if (!name.matches("[a-z0-9_][a-z0-9_.-]*")) {
                CraftPy.LOGGER.warn("Rejected raw datapack namespace: {}", name);
                return;
            }
            base = data.getParent().resolve(name);
        }
        Path target = base.resolve(path).normalize();
        if (!target.startsWith(base.normalize())) {
            CraftPy.LOGGER.warn("Rejected out-of-tree raw datapack path: {}", entry.id());
            return;
        }
        Files.createDirectories(target.getParent());
        writeString(target, Json.GSON.toJson(definition));
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

    /** Project (or module) id of a slot holder key such as {@code BLOCK:ruby_rush:ruby_ore}. */
    static String ownerOf(String holder) {
        String rest = holder;
        if (rest.startsWith("BLOCK:") || rest.startsWith("ITEM:")) {
            rest = rest.substring(rest.indexOf(':') + 1);
        }
        int colon = rest.indexOf(':');
        return colon > 0 ? rest.substring(0, colon) : "craftpy";
    }

    /**
     * Turns what a script wrote into a texture id Minecraft resolves:
     * {@code ruby.png} / {@code ruby} = the project's {@code assets/textures/ruby.png};
     * {@code minecraft:dirt} = {@code minecraft:block/dirt} (or {@code item/...} for items, when that exists);
     * anything with a folder ({@code minecraft:block/dirt}, {@code ruby_rush:items/gem}) is kept.
     */
    static String texturePath(String texture, String projectId, String kind) {
        if (texture == null || texture.isBlank()) {
            return UNLOADED_TEXTURE;
        }
        String value = texture.trim();
        if (value.endsWith(".png")) {
            value = value.substring(0, value.length() - 4);
        }
        if (value.startsWith("textures/")) {
            value = value.substring("textures/".length());
        }
        int colon = value.indexOf(':');
        if (colon < 0) {
            return projectId + ":" + value;
        }
        String ns = value.substring(0, colon);
        String path = value.substring(colon + 1);
        if (path.contains("/") || !"minecraft".equals(ns)) {
            return value;
        }
        // Vanilla shorthand: prefer the folder matching the content kind, fall back to the other one.
        String other = "block".equals(kind) ? "item" : "block";
        if (vanillaTextureExists(kind, path) || !vanillaTextureExists(other, path)) {
            return "minecraft:" + kind + "/" + path;
        }
        return "minecraft:" + other + "/" + path;
    }

    /** Checks the vanilla jar when it is on the classpath (client/singleplayer); a dedicated server says no. */
    private static boolean vanillaTextureExists(String folder, String path) {
        // Class.getResource would only search this mod's module; the class loader sees the game layer too.
        ClassLoader loader = PackBuilder.class.getClassLoader();
        return loader != null && loader.getResource("assets/minecraft/textures/" + folder + "/" + path + ".png") != null;
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