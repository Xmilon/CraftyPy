package net.xmilon.craftpy.dynamics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.UnaryOperator;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

/**
 * Structure templates ({@code .nbt}, as structure blocks save them) and CraftPy's own blocks inside them. A placed CraftPy
 * block is a slot ({@code craftpy:pcblock_7}), and slots differ from one game to the next. So a template keeps
 * {@code craftpy_content:<project>/<block id>} instead (written when it is imported), and every pack build turns that
 * back into the slot this game uses.
 */
public final class StructureFiles {
    /** Block names in a template that stand for a project block. */
    public static final String CONTENT_NAMESPACE = "craftpy_content";
    /** Largest template file read (a 48 x 48 x 48 build is far below). */
    static final long MAX_BYTES = 32L * 1024 * 1024;

    private StructureFiles() {
    }

    public static CompoundTag read(Path file) throws IOException {
        if (Files.size(file) > MAX_BYTES) {
            throw new IOException(file.getFileName() + " is larger than " + MAX_BYTES / 1024 / 1024 + " MiB");
        }
        return NbtIo.readCompressed(file, NbtAccounter.create(MAX_BYTES * 4));
    }

    public static void write(CompoundTag tag, Path file) throws IOException {
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(tag, file);
    }

    /** Renames the blocks of every palette ({@code palette}, and {@code palettes} of templates with variants). */
    public static boolean renameBlocks(CompoundTag template, UnaryOperator<String> rename) {
        boolean changed = false;
        if (template.contains("palette", Tag.TAG_LIST)) {
            changed |= renamePalette(template.getList("palette", Tag.TAG_COMPOUND), rename);
        }
        if (template.contains("palettes", Tag.TAG_LIST)) {
            ListTag palettes = template.getList("palettes", Tag.TAG_LIST);
            for (int i = 0; i < palettes.size(); i++) {
                changed |= renamePalette(palettes.getList(i), rename);
            }
        }
        return changed;
    }

    private static boolean renamePalette(ListTag palette, UnaryOperator<String> rename) {
        boolean changed = false;
        for (int i = 0; i < palette.size(); i++) {
            CompoundTag state = palette.getCompound(i);
            String name = state.getString("Name");
            String renamed = rename.apply(name);
            if (renamed != null && !renamed.equals(name)) {
                state.putString("Name", renamed);
                if (!renamed.startsWith("craftpy:pcblock_")) {
                    state.remove("Properties"); // the slot's light property means nothing on another block
                }
                changed = true;
            }
        }
        return changed;
    }

    /** {@code craftpy_content:<project>/<id>} for a slot holder {@code BLOCK:<project>:<id>}. */
    public static String contentName(String holder) {
        String rest = holder.startsWith("BLOCK:") ? holder.substring("BLOCK:".length()) : holder;
        return CONTENT_NAMESPACE + ":" + rest.replaceFirst(":", "/");
    }
}
