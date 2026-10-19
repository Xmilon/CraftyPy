package net.xmilon.craftpy.catalog;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;
import net.xmilon.craftpy.CraftPyLog;

/**
 * Writes the {@link Catalog} of every loaded mod once the server has started, so the IDE can complete and check names of
 * mods it has never seen. Silent: it only works for mods whose version or jar changed since the last dump (a normal start
 * costs a few hash look-ups). Runs on the server thread because recipes and datapack registries belong to it.
 */
public final class CatalogDump {
    /** Bump when the dumped data changes shape: every catalog is then made again. */
    static final int FORMAT = 2;
    private static final int SAMPLED_RECIPES = 20;
    /** Dynamic (datapack) registries of vanilla worth completing; mods' own datapack registries are always taken. */
    private static final Set<String> VANILLA_DYNAMIC = Set.of("enchantment", "damage_type", "jukebox_song", "painting_variant",
            "banner_pattern", "trim_material", "trim_pattern", "instrument", "wolf_variant");
    /** Not scripting targets: project content is handled by the project scan, the UI mod has nothing to offer. */
    private static final Set<String> IGNORED_MODS = Set.of("craftpy", "modernui");

    private record Owner(String mod, String version, String hash, Path jar) {
    }

    private CatalogDump() {
    }

    public static void onServerStarted(MinecraftServer server) {
        try {
            ModCatalog catalog = ModCatalog.get();
            Map<String, Owner> owners = owners(catalog.store());
            Set<String> installed = new TreeSet<>(owners.keySet());
            catalog.setInstalled(() -> installed);
            Set<String> stale = new HashSet<>();
            for (Owner owner : owners.values()) {
                if (!catalog.store().isCurrent(owner.mod(), owner.version(), owner.hash())) {
                    stale.add(owner.mod());
                }
            }
            if (stale.isEmpty()) {
                return;
            }
            long started = System.currentTimeMillis();
            Map<String, Catalog> made = collect(server, owners, stale);
            for (Catalog result : made.values()) {
                catalog.store().save(result);
            }
            catalog.invalidate();
            CraftPyLog.LOGGER.info("Catalog: {} namespace(s) of {} mod(s) written in {} ms", made.size(), stale.size(),
                    System.currentTimeMillis() - started);
        } catch (Exception | LinkageError e) {
            CraftPyLog.LOGGER.warn("Could not write the mod catalog: {}", e.toString());
        }
    }

    private static Map<String, Owner> owners(CatalogStore store) {
        Map<String, Owner> owners = new TreeMap<>();
        for (IModInfo info : ModList.get().getMods()) {
            String id = info.getModId();
            if (IGNORED_MODS.contains(id)) {
                continue;
            }
            Path jar = null;
            String hash;
            try {
                jar = info.getOwningFile().getFile().getFilePath();
                hash = store.hashOf(jar);
            } catch (Exception e) {
                hash = "version-" + info.getVersion(); // a jar inside a jar cannot be hashed on its own
            }
            owners.put(id, new Owner(id, info.getVersion().toString(), hash + "#" + FORMAT, jar));
        }
        String minecraft = SharedConstants.getCurrentVersion().getName();
        owners.put("minecraft", new Owner("minecraft", minecraft, "mc-" + minecraft + "#" + FORMAT, null));
        return owners;
    }

    private static Map<String, Catalog> collect(MinecraftServer server, Map<String, Owner> owners, Set<String> stale) {
        Map<String, Catalog> byNamespace = new TreeMap<>();
        java.util.function.Function<String, Catalog> catalogFor = namespace -> {
            String mod = owners.containsKey(namespace) ? namespace : namespace.equals("c") ? "neoforge" : null;
            if (mod == null || !owners.containsKey(mod) || !stale.contains(mod)) {
                return null;
            }
            return byNamespace.computeIfAbsent(namespace, ns -> {
                Catalog catalog = new Catalog();
                Owner owner = owners.get(mod);
                catalog.namespace = ns;
                catalog.mod = mod;
                catalog.version = owner.version();
                catalog.jarHash = owner.hash();
                return catalog;
            });
        };
        for (String mod : stale) {
            catalogFor.apply(mod); // a mod without registry entries still gets its (empty) catalog, so it counts as dumped
        }

        for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
            addRegistry(registry, true, catalogFor);
        }
        BuiltInRegistries.BLOCK.forEach(block -> {
            var id = BuiltInRegistries.BLOCK.getKey(block); Catalog catalog = catalogFor.apply(id.getNamespace());
            if (catalog == null) return;
            JsonObject info = registeredInfo(catalog, block.getClass());
            info.addProperty("compatible", true);
            info.addProperty("reuse", "NativeBlock aliases this original registered block/item; machine behavior and renderer remain native.");
            JsonObject properties = new JsonObject();
            block.getStateDefinition().getProperties().forEach(property -> {
                var values = new com.google.gson.JsonArray(); property.getPossibleValues().forEach(value -> values.add(value.toString()));
                properties.add(property.getName(), values);
            });
            info.add("block_state_properties", properties); catalog.capabilities.put("block:" + id, info);
        });
        BuiltInRegistries.ENTITY_TYPE.forEach(type -> {
            var id = BuiltInRegistries.ENTITY_TYPE.getKey(type); Catalog catalog = catalogFor.apply(id.getNamespace());
            if (catalog == null) return;
            JsonObject info = registeredInfo(catalog, type.getBaseClass());
            info.addProperty("concrete_class", "unknown: the registered entity factory is not executed by inspection");
            info.addProperty("compatible", net.minecraft.world.entity.ai.attributes.DefaultAttributes.hasSupplier(type));
            info.addProperty("reuse", "NativeMob uses the original registered entity type, retaining its AI and renderer. No arbitrary class inheritance is inferred.");
            JsonObject attributes = new JsonObject();
            if (net.minecraft.world.entity.ai.attributes.DefaultAttributes.hasSupplier(type)) {
                var supplier = net.minecraft.world.entity.ai.attributes.DefaultAttributes.getSupplier((net.minecraft.world.entity.EntityType) type);
                BuiltInRegistries.ATTRIBUTE.holders().forEach(attribute -> {
                    if (supplier.hasAttribute(attribute)) attributes.addProperty(attribute.key().location().toString(), supplier.getBaseValue(attribute));
                });
            }
            info.add("attributes", attributes); catalog.capabilities.put("entity_type:" + id, info);
        });
        server.registryAccess().registries().forEach(entry -> addRegistry(entry.value(), false, catalogFor));

        for (ResourceLocation type : BuiltInRegistries.RECIPE_TYPE.keySet()) {
            Catalog catalog = catalogFor.apply(type.getNamespace());
            if (catalog != null) {
                catalog.recipeTypes.computeIfAbsent(type.toString(), k -> new Catalog.RecipeType()).source = "dump";
            }
        }
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        Map<String, Set<String>> fields = new HashMap<>();
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            ResourceLocation type = BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType());
            Catalog catalog = type == null ? null : catalogFor.apply(type.getNamespace());
            if (catalog == null) {
                continue;
            }
            Catalog.RecipeType schema = catalog.recipeTypes.computeIfAbsent(type.toString(), k -> new Catalog.RecipeType());
            schema.source = "dump";
            schema.count++;
            if (schema.count <= SAMPLED_RECIPES) {
                sample(schema, fields.computeIfAbsent(type.toString(), k -> new TreeSet<>()), holder.value(), ops);
            }
        }
        fields.forEach((type, names) -> {
            Catalog catalog = byNamespace.get(ResourceLocation.parse(type).getNamespace());
            if (catalog != null) {
                catalog.recipeTypes.get(type).fields = new ArrayList<>(names);
            }
        });

        for (Catalog catalog : byNamespace.values()) {
            Owner owner = owners.get(catalog.mod);
            if (owner.jar() != null && catalog.namespace.equals(catalog.mod)) {
                catalog.classes = ModJars.classNames(owner.jar());
                catalog.events = ModJars.eventNames(catalog.classes);
                for (String name : catalog.classes) {
                    if (!name.endsWith("Goal") && !name.contains(".goal.")) continue;
                    try {
                        Class<?> type = Class.forName(name, false, CatalogDump.class.getClassLoader());
                        if (net.minecraft.world.entity.ai.goal.Goal.class.isAssignableFrom(type))
                            catalog.capabilities.put("goal:" + name, NativeInspection.inspect(type, net.xmilon.craftpy.entity.SlottedEntity.class));
                    } catch (ClassNotFoundException | LinkageError | RuntimeException ignored) {
                        // A missing optional dependency is not evidence of constructor compatibility.
                    }
                }
            }
        }
        return byNamespace;
    }

    private static JsonObject registeredInfo(Catalog catalog, Class<?> type) {
        catalog.capabilities.computeIfAbsent("class:" + type.getName(), key ->
                NativeInspection.inspect(type, net.xmilon.craftpy.entity.SlottedEntity.class));
        JsonObject info = new JsonObject(); info.addProperty("class", type.getName());
        info.addProperty("base_class", type.getSuperclass() == null ? "" : type.getSuperclass().getName());
        info.addProperty("discovered", true); info.addProperty("verified", false);
        return info;
    }

    private static void sample(Catalog.RecipeType schema, Set<String> names, Recipe<?> recipe, RegistryOps<JsonElement> ops) {
        try {
            JsonElement encoded = Recipe.CODEC.encodeStart(ops, recipe).result().orElse(null);
            if (encoded == null || !encoded.isJsonObject()) {
                return;
            }
            JsonObject object = encoded.getAsJsonObject().deepCopy();
            object.remove("type");
            names.addAll(object.keySet());
            if (schema.sample == null) {
                schema.sample = object;
            }
        } catch (RuntimeException e) {
            // a recipe that cannot be written back tells nothing about its layout
        }
    }

    private static void addRegistry(Registry<?> registry, boolean builtIn, java.util.function.Function<String, Catalog> catalogFor) {
        ResourceLocation key = registry.key().location();
        String kind = key.getNamespace().equals("minecraft") ? key.getPath() : key.toString();
        boolean vanilla = key.getNamespace().equals("minecraft");
        if (kind.startsWith("worldgen/") || (builtIn && kind.startsWith("loot_"))
                || (!builtIn && vanilla && !VANILLA_DYNAMIC.contains(kind))) {
            return;
        }
        for (ResourceLocation id : registry.keySet()) {
            Catalog catalog = catalogFor.apply(id.getNamespace());
            if (catalog != null && !id.getPath().startsWith("pcblock_") && !id.getPath().startsWith("pcitem_")) {
                catalog.registries.computeIfAbsent(kind, k -> new ArrayList<>()).add(id.toString());
            }
        }
        try {
            registry.getTagNames().forEach(tag -> {
                Catalog catalog = catalogFor.apply(tag.location().getNamespace());
                if (catalog != null) {
                    catalog.tags.computeIfAbsent(kind, k -> new ArrayList<>()).add(tag.location().toString());
                }
            });
        } catch (RuntimeException e) {
            // a registry without tags
        }
    }
}
