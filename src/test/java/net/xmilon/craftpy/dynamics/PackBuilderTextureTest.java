package net.xmilon.craftpy.dynamics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Texture references written in scripts must become ids Minecraft can resolve. */
class PackBuilderTextureTest {

    @Test
    void ownerComesFromTheHolderNotTheKindPrefix() {
        assertEquals("ruby_rush", PackBuilder.ownerOf("BLOCK:ruby_rush:ruby_ore"));
        assertEquals("ruby_rush", PackBuilder.ownerOf("ITEM:ruby_rush:ruby"));
        assertEquals("demo", PackBuilder.ownerOf("demo:gem"));
        assertEquals("craftpy", PackBuilder.ownerOf("block_7"));
    }

    @Test
    void projectTexturesGetTheProjectNamespace() {
        assertEquals("ruby_rush:ruby", PackBuilder.texturePath("ruby.png", "ruby_rush", "item"));
        assertEquals("ruby_rush:ruby", PackBuilder.texturePath("ruby", "ruby_rush", "item"));
        assertEquals("ruby_rush:block/ore", PackBuilder.texturePath("textures/block/ore.png", "ruby_rush", "block"));
    }

    @Test
    void fullIdsAreKept() {
        assertEquals("minecraft:block/dirt", PackBuilder.texturePath("minecraft:block/dirt", "p", "block"));
        assertEquals("other:thing", PackBuilder.texturePath("other:thing", "p", "item"));
    }

    @Test
    void vanillaShorthandUsesTheKindFolder() {
        // Without the vanilla jar on the test classpath the kind folder is the answer.
        String block = PackBuilder.texturePath("minecraft:deepslate_gold_ore", "p", "block");
        String item = PackBuilder.texturePath("minecraft:diamond", "p", "item");
        assertEquals(true, block.startsWith("minecraft:") && block.endsWith("/deepslate_gold_ore"));
        assertEquals(true, item.startsWith("minecraft:") && item.endsWith("/diamond"));
    }

    @Test
    void projectTexturesOutsideBlockAndItemFoldersJoinTheBlockAtlas(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root)
            throws Exception {
        java.nio.file.Path textures = root.resolve("assets/welcome/textures");
        java.nio.file.Files.createDirectories(textures.resolve("block"));
        java.nio.file.Files.write(textures.resolve("gem.png"), new byte[] {1});
        java.nio.file.Files.write(textures.resolve("block/ore.png"), new byte[] {1});
        java.nio.file.Files.createDirectories(root.resolve("assets/minecraft/textures/entity"));
        java.nio.file.Files.write(root.resolve("assets/minecraft/textures/entity/pig.png"), new byte[] {1});

        var atlas = PackBuilder.blockAtlas(root, java.util.Set.of("welcome:gem", "other:things/x", "minecraft:block/dirt"));
        java.util.List<String> sprites = new java.util.ArrayList<>();
        atlas.getAsJsonArray("sources").forEach(s -> {
            assertEquals("single", s.getAsJsonObject().get("type").getAsString());
            sprites.add(s.getAsJsonObject().get("resource").getAsString());
        });
        // block/ and item/ are stitched by vanilla; retextured vanilla files are left to their own atlases
        assertEquals(java.util.List.of("other:things/x", "welcome:gem"), sprites);
        assertEquals(true, PackBuilder.blockAtlas(root.resolve("none"), java.util.Set.of()).entrySet().isEmpty());
    }

    @Test
    void needsToolAddsAMatchToolCondition() {
        com.google.gson.JsonObject table = PackBuilder.selfDropLoot("craftpy:pcblock_1");
        PackBuilder.requireTool(table, "pickaxe");
        var condition = table.getAsJsonArray("pools").get(0).getAsJsonObject()
                .getAsJsonArray("conditions").get(0).getAsJsonObject();
        assertEquals("minecraft:match_tool", condition.get("condition").getAsString());
        assertEquals("#minecraft:pickaxes", condition.getAsJsonObject("predicate").get("items").getAsString());
    }
}
