package net.xmilon.craftpy.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.LevelResource;
import net.xmilon.craftpy.dynamics.StructureFiles;
import net.xmilon.craftpy.project.ProjectManager;
import net.xmilon.craftpy.registry.SlotRegistry;

/**
 * Structure templates in the world, any time: a template of a project ({@code ruins/house} = {@code data/structure/ruins/house.nbt})
 * or of the game, and jigsaw generation from a template pool (pools are world generation, so they exist once the world
 * was opened with the project loaded). Also imports what a structure block saved into a project. Server thread.
 */
public final class StructurePlacer {
    private StructurePlacer() {
    }

    /** Null when it was placed, otherwise why not. */
    public static String placeTemplate(ServerLevel level, ResourceLocation id, BlockPos pos, int rotation, boolean mirror,
                                       boolean entities) {
        StructureTemplate template;
        try {
            template = level.getStructureManager().get(id).orElse(null);
        } catch (RuntimeException e) {
            return "Not a structure id: " + id;
        }
        if (template == null) {
            return "No structure " + id + " (a project's data/structure/<name>.nbt, after Run)";
        }
        if (!loaded(level, pos, pos.offset(template.getSize()))) {
            return "That place is not loaded: stand near it";
        }
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(rotation(rotation))
                .setMirror(mirror ? Mirror.FRONT_BACK : Mirror.NONE)
                .setIgnoreEntities(!entities);
        boolean placed = template.placeInWorld(level, pos, pos, settings, StructureBlockEntity.createRandom(level.getRandom().nextLong()), 2);
        return placed ? null : "The structure could not be placed there";
    }

    /** Jigsaw generation from {@code pool}, starting at the jigsaw block named {@code target} in its first piece. */
    public static String placeJigsaw(ServerLevel level, ResourceLocation pool, ResourceLocation target, int depth, BlockPos pos) {
        var registry = level.registryAccess().registryOrThrow(Registries.TEMPLATE_POOL);
        var holder = registry.getHolder(ResourceKey.create(Registries.TEMPLATE_POOL, pool)).orElse(null);
        if (holder == null) {
            return "No template pool " + pool + ": pools are read when the world opens; reopen the world after the first Run";
        }
        if (!loaded(level, pos, pos)) {
            return "That place is not loaded: stand near it";
        }
        return JigsawPlacement.generateJigsaw(level, holder, target, Math.max(1, Math.min(20, depth)), pos, false)
                ? null : "The jigsaw structure could not be generated there";
    }

    private static boolean loaded(ServerLevel level, BlockPos from, BlockPos to) {
        ChunkPos a = new ChunkPos(from);
        ChunkPos b = new ChunkPos(to);
        for (int x = Math.min(a.x, b.x); x <= Math.max(a.x, b.x); x++) {
            for (int z = Math.min(a.z, b.z); z <= Math.max(a.z, b.z); z++) {
                if (!level.isLoaded(new ChunkPos(x, z).getWorldPosition())) {
                    return false;
                }
            }
        }
        return true;
    }

    static Rotation rotation(int degrees) {
        return switch (Math.floorMod(degrees, 360) / 90) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    /**
     * Copies what a structure block saved as {@code name} ({@code house} or {@code ns:path}, from the world's
     * {@code generated/<ns>/structures/}) into {@code <project>/data/structure/<path>.nbt}; CraftPy blocks in it are
     * written as content ids so the template works in any game. Returns a message for the player.
     */
    public static String importSaved(MinecraftServer server, String name, String projectId) throws IOException {
        ResourceLocation id = ResourceLocation.tryParse(name.contains(":") ? name : "minecraft:" + name);
        ProjectManager manager = ProjectManager.get();
        if (id == null || manager == null || !ProjectManager.isValidId(projectId)
                || !Files.isDirectory(manager.projectsDir().resolve(projectId))) {
            return "Unknown project or structure name";
        }
        Path generated = server.getWorldPath(LevelResource.GENERATED_DIR).resolve(id.getNamespace()).resolve("structures");
        Path file = generated.resolve(id.getPath() + ".nbt").normalize();
        if (!file.startsWith(generated) || !Files.isRegularFile(file)) {
            return "No saved structure '" + id + "' in this world (save it with a structure block first)";
        }
        CompoundTag template = StructureFiles.read(file);
        Map<SlotRegistry.SlotRef, String> holders = new java.util.HashMap<>();
        SlotRegistry.snapshotByHolder().forEach((holder, slot) -> {
            if (slot.kind() == SlotRegistry.Kind.BLOCK || slot.kind() == SlotRegistry.Kind.KINETIC) {
                holders.putIfAbsent(slot, holder);
            }
        });
        int[] mapped = {0};
        StructureFiles.renameBlocks(template, block -> {
            boolean kinetic = block.startsWith("craftpy:pckinetic_");
            if (!kinetic && !block.startsWith("craftpy:pcblock_")) {
                return block;
            }
            try {
                String holder = holders.get(new SlotRegistry.SlotRef(kinetic ? SlotRegistry.Kind.KINETIC : SlotRegistry.Kind.BLOCK,
                        Integer.parseInt(block.substring((kinetic ? "craftpy:pckinetic_" : "craftpy:pcblock_").length()))));
                if (holder != null) {
                    mapped[0]++;
                    return StructureFiles.contentName(holder);
                }
            } catch (NumberFormatException ignored) {
                // not a slot
            }
            return block;
        });
        Path target = manager.projectsDir().resolve(projectId).resolve("data").resolve("structure").resolve(id.getPath() + ".nbt");
        StructureFiles.write(template, target);
        return "Imported " + id + " into " + projectId + " as " + projectId + ":" + id.getPath()
                + (mapped[0] > 0 ? " (" + mapped[0] + " CraftPy block kinds kept as content ids)" : "") + ". Press Run to use it.";
    }
}
