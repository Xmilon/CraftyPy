package net.xmilon.craftpy.dynamics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class PackBuilderAssetsTest {
    @Test
    void resourceFoldersAndLooseFilesBelongToTheProjectNamespace() {
        assertEquals("ruby_rush", PackBuilder.assetNamespace("ruby_rush", "textures", true));
        assertEquals("ruby_rush", PackBuilder.assetNamespace("ruby_rush", "models", true));
        assertEquals("ruby_rush", PackBuilder.assetNamespace("ruby_rush", "sounds.json", false));
    }

    @Test
    void otherFoldersAreNamespacesLikeInAResourcePack() {
        assertEquals("minecraft", PackBuilder.assetNamespace("ruby_rush", "minecraft", true));
        assertEquals("other_mod", PackBuilder.assetNamespace("ruby_rush", "other_mod", true));
        assertNull(PackBuilder.assetNamespace("ruby_rush", "Bad Name", true));
    }

    @Test
    void blocksWithAMinimumTierAskForTheLevelTag() {
        var table = com.google.gson.JsonParser.parseString(
                "{\"pools\":[{\"rolls\":1,\"entries\":[]}]}").getAsJsonObject();
        PackBuilder.requireTool(table, "pickaxe", 2);
        String condition = table.getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonArray("conditions").toString();
        org.junit.jupiter.api.Assertions.assertTrue(condition.contains("#craftpy:level/pickaxe/2"), condition);
        var plain = com.google.gson.JsonParser.parseString(
                "{\"pools\":[{\"rolls\":1,\"entries\":[]}]}").getAsJsonObject();
        PackBuilder.requireTool(plain, "axe", 0);
        org.junit.jupiter.api.Assertions.assertTrue(plain.toString().contains("#minecraft:axes"));
    }
}
