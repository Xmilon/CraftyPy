package net.xmilon.craftpy.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class ModelSceneGeoTest {
    private static final String CUBE = """
            {"format_version": "1.12.0", "minecraft:geometry": [{
              "description": {"identifier": "geometry.cube", "texture_width": 64, "texture_height": 32},
              "bones": [{"name": "body", "pivot": [0, 0, 0], "cubes": [{"origin": [-4, 0, -4], "size": [8, 8, 8], "uv": [0, 0]}]}]
            }]}""";

    @Test
    void boxUvCubeHasSixFacesWithGeckoLibUvs() {
        JsonObject json = JsonParser.parseString(CUBE).getAsJsonObject();
        assertTrue(ModelScene.isGeo(json));
        ModelScene scene = ModelScene.geo(json, "test:entity/cube");
        assertEquals(6, scene.faces().size());
        assertTrue(scene.problems().isEmpty(), scene.problems().toString());
        // GeckoLib box uv: up = (u + z, v) size (x, z)
        assertArrayEquals(new double[]{8, 0, 16, 8}, ModelScene.geoUv(JsonParser.parseString("[0, 0]"), "up", new double[]{8, 8, 8}, false));
        for (ModelScene.Face face : scene.faces()) {
            for (double[] c : face.corners()) {
                for (double v : c) {
                    assertTrue(v >= -1e-6 && v <= 16 + 1e-6, "fitted into the 0..16 box");
                }
            }
        }
    }

    @Test
    void realGeckoLibFilesLoad() throws Exception {
        Path dir = Path.of("../Compatibily Mods/irons-spells-n-spellbooks/src/main/resources/assets/irons_spellbooks/geo");
        if (!Files.isDirectory(dir)) {
            return; // the compat sources are only on the developer's machine
        }
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".geo.json")).toList()) {
                JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                ModelScene scene = ModelScene.geo(json, "irons_spellbooks:entity/x");
                assertTrue(!scene.faces().isEmpty() || !scene.problems().isEmpty(), file.toString());
            }
        }
    }
}
