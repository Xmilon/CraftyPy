package net.xmilon.craftpy.content;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.xmilon.craftpy.project.ModuleLoader;
import net.xmilon.craftpy.project.PyForgePaths;
import net.xmilon.craftpy.python.Declarations;
import net.xmilon.craftpy.registry.SlotRegistry;
import net.xmilon.craftpy.util.Json;

/** Native aliases have no slot and never replace an already placed block or spawned entity. */
public final class NativeContent {
    public record Definition(String owner, String id, String kind, String base, JsonObject data) { }
    public record Plan(Map<String, Definition> active, JsonObject families) { }
    private static Map<String, Definition> active = Map.of();
    private static JsonObject families = new JsonObject();
    private static Path loadedFile;
    private NativeContent() { }

    private static void load() {
        Path file = PyForgePaths.system().resolve("native-families.json").toAbsolutePath();
        if (file.equals(loadedFile)) return;
        try { families = Files.exists(file) ? Json.read(file, JsonObject.class) : new JsonObject(); }
        catch (IOException | RuntimeException error) { throw new IllegalStateException("Cannot read native family identities; repair " + file + " before loading content", error); }
        active = Map.of(); loadedFile = file;
    }

    public static synchronized Plan plan(String owner, Declarations declarations, Map<String, SlotRegistry.SlotRef> slots) {
        load();
        Map<String, Definition> next = new LinkedHashMap<>(active); next.values().removeIf(d -> d.owner().equals(owner));
        JsonObject identities = families.deepCopy();
        for (var content : declarations.contents()) {
            if (!content.kind().equals("block") && !content.kind().equals("entity")) continue;
            String fullId = owner + ":" + content.id(), key = content.kind() + ":" + fullId;
            boolean nativeBase = content.definition().has("native_base");
            if (!nativeBase && identities.has(key)) throw migration(fullId);
            if (!nativeBase) continue;
            if (slots.containsKey("BLOCK:" + fullId) || slots.containsKey("KINETIC:" + fullId) || slots.containsKey("ENTITY:" + fullId) || slots.containsKey("MONSTER:" + fullId)) throw migration(fullId);
            String base = content.definition().get("native_base").getAsString();
            ResourceLocation location = ResourceLocation.tryParse(base);
            if (location == null || SlotRegistry.isPlaceholder(location)) throw new IllegalArgumentException("Invalid native base: " + base);
            if (content.kind().equals("block")) {
                if (!BuiltInRegistries.BLOCK.containsKey(location) || BuiltInRegistries.BLOCK.get(location).asItem() == net.minecraft.world.item.Items.AIR)
                    throw new IllegalArgumentException("NativeBlock base needs an installed block with an item: " + base);
            } else {
                if (!BuiltInRegistries.ENTITY_TYPE.containsKey(location) || !DefaultAttributes.hasSupplier(BuiltInRegistries.ENTITY_TYPE.get(location)))
                    throw new IllegalArgumentException("NativeMob base needs an installed living entity type with native attributes: " + base);
                JsonObject state = content.definition().has("state") ? content.definition().getAsJsonObject("state") : new JsonObject();
                if (Json.COMPACT.toJson(state).length() > 16384) throw new IllegalArgumentException("NativeMob state exceeds 16 KiB");
            }
            identities.addProperty(key, "native_" + content.kind());
            next.put(key, new Definition(owner, fullId, content.kind(), base, content.definition().deepCopy()));
        }
        return new Plan(Map.copyOf(next), identities);
    }

    private static IllegalStateException migration(String id) {
        return new IllegalStateException("Content '" + id + "' cannot change native slot family without an explicit migration. Use a new content ID.");
    }

    /** Persist identities before publishing definitions; a failed write leaves the previous active version intact. */
    public static synchronized void commit(Plan plan) {
        try {
            Files.createDirectories(loadedFile.getParent()); Path temporary = loadedFile.resolveSibling("native-families.json.tmp");
            Files.writeString(temporary, Json.GSON.toJson(plan.families()));
            try { Files.move(temporary, loadedFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temporary, loadedFile, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException error) { throw new IllegalStateException("Cannot save native family identities", error); }
        families = plan.families(); active = plan.active();
    }

    public static synchronized void release(String owner) {
        Map<String, Definition> next = new LinkedHashMap<>(active); next.values().removeIf(d -> d.owner().equals(owner)); active = Map.copyOf(next);
    }

    public static synchronized Definition definition(String kind, String id) { return active.get(kind + ":" + id); }
    public static synchronized java.util.List<Definition> definitions(String kind) { return active.values().stream().filter(d -> d.kind().equals(kind)).toList(); }

    public static String resolveBlock(String id) {
        Definition definition = definition("block", id); return definition == null ? id : definition.base();
    }

    public static Entity spawn(String id, ServerLevel level, BlockPos position) {
        Definition definition = definition("entity", id);
        if (definition == null) return null;
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(definition.base()));
        Entity entity = type.spawn(level, position, MobSpawnType.COMMAND);
        if (entity == null) return null;
        String workerOwner = ModuleLoader.isLoaded(definition.owner()) ? "module:" + definition.owner() : definition.owner();
        var variant = new net.minecraft.nbt.CompoundTag(); variant.putString("id", definition.id()); variant.putString("owner", workerOwner);
        variant.putString("base", definition.base()); entity.getPersistentData().put("craftpy:variant", variant);
        if (definition.data().has("state")) NativeEntityState.put(entity, workerOwner, definition.data().getAsJsonObject("state"));
        if (definition.data().has("name")) entity.setCustomName(Component.literal(definition.data().get("name").getAsString()));
        return entity;
    }

    public static String logicalId(Entity entity) {
        var variant = entity.getPersistentData().getCompound("craftpy:variant");
        if (variant.contains("id")) return variant.getString("id");
        return entity instanceof net.xmilon.craftpy.entity.SlottedEntity mob
                ? net.xmilon.craftpy.lifecycle.EventBridge.projectId(mob.holder()) : BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }
}
