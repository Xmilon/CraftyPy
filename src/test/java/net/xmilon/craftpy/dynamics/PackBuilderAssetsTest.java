package net.xmilon.craftpy.dynamics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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

    @Test
    void declaredSoundsAreAddedToTheProjectsOwnSoundsJson(@TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path own = dir.resolve("sounds.json");
        java.nio.file.Files.writeString(own, "{\"mine\":{\"sounds\":[\"a\"]},\"both\":{\"sounds\":[\"old\"]}}");
        JsonObject declared = JsonParser.parseString("{\"both\":{\"sounds\":[\"new\"]},\"extra\":{\"sounds\":[\"b\"]}}").getAsJsonObject();
        JsonObject merged = PackBuilder.mergeSounds(own, declared);
        assertEquals(3, merged.size());
        assertEquals("a", merged.getAsJsonObject("mine").getAsJsonArray("sounds").get(0).getAsString());
        assertEquals("new", merged.getAsJsonObject("both").getAsJsonArray("sounds").get(0).getAsString());
        // no file of its own: just the declared ones
        assertEquals(2, PackBuilder.mergeSounds(dir.resolve("none.json"), declared).size());
    }

    @Test
    void rawJsonCanTargetAnotherNamespaceButNotEscape(@TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path data = dir.resolve("data").resolve("demo");
        var other = new net.xmilon.craftpy.python.Declarations.DatapackEntry("raw", "tags/item/x.json",
                JsonParser.parseString("{\"definition\":{\"values\":[]},\"namespace\":\"minecraft\"}"));
        PackBuilder.writeRawFile(data, other);
        org.junit.jupiter.api.Assertions.assertTrue(java.nio.file.Files.exists(dir.resolve("data/minecraft/tags/item/x.json")));
        var escape = new net.xmilon.craftpy.python.Declarations.DatapackEntry("raw", "../../evil.json",
                JsonParser.parseString("{\"definition\":{},\"namespace\":\"minecraft\"}"));
        PackBuilder.writeRawFile(data, escape);
        var badNamespace = new net.xmilon.craftpy.python.Declarations.DatapackEntry("raw", "a.json",
                JsonParser.parseString("{\"definition\":{},\"namespace\":\"..\"}"));
        PackBuilder.writeRawFile(data, badNamespace);
        org.junit.jupiter.api.Assertions.assertFalse(java.nio.file.Files.exists(dir.resolve("evil.json")));
        org.junit.jupiter.api.Assertions.assertFalse(java.nio.file.Files.exists(dir.resolve("a.json")));
        // without a namespace it stays inside the project's own folder, as before
        PackBuilder.writeRawFile(data, new net.xmilon.craftpy.python.Declarations.DatapackEntry("raw", "own.json",
                JsonParser.parseString("{\"definition\":{}}")));
        org.junit.jupiter.api.Assertions.assertTrue(java.nio.file.Files.exists(data.resolve("own.json")));
    }
}
