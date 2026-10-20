package net.xmilon.craftpy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class ModelSceneTest {
    private static final Map<String, String> MODELS = Map.of(
            "minecraft:block/cube_all", """
                    {"parent":"minecraft:block/cube","textures":{"particle":"#all","down":"#all","up":"#all",
                     "north":"#all","east":"#all","south":"#all","west":"#all"}}""",
            "minecraft:block/cube", """
                    {"elements":[{"from":[0,0,0],"to":[16,16,16],"faces":{
                     "down":{"texture":"#down"},"up":{"texture":"#up"},"north":{"texture":"#north"},
                     "south":{"texture":"#south"},"west":{"texture":"#west"},"east":{"texture":"#east"}}}],
                     "display":{"gui":{"rotation":[30,225,0],"scale":[0.625,0.625,0.625]}}}""");

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static ModelScene scene(String model) {
        return ModelScene.build(json(model), id -> MODELS.containsKey(id) ? json(MODELS.get(id)) : null);
    }

    private static final String ALL = "{\"parent\":\"minecraft:block/cube_all\",\"textures\":{\"all\":\"minecraft:block/stone\"}}";

    @Test
    void parentsAndTextureVariablesAreResolved() {
        var scene = scene(ALL);
        assertEquals(6, scene.faces().size());
        assertEquals(List.of("minecraft:block/stone"), scene.textures());
        assertTrue(scene.problems().isEmpty(), scene.problems().toString());
        assertEquals(0.625, scene.display("gui").scale()[0]);
        assertEquals(1.0, scene.display("fixed").scale()[0], "missing contexts are the identity");
    }

    @Test
    void childTexturesAndMissingParentsAreReported() {
        var missing = scene("{\"parent\":\"mod:block/nothing\"}");
        assertTrue(missing.faces().isEmpty());
        assertTrue(missing.problems().get(0).contains("mod:block/nothing"));
        var undefined = scene("{\"parent\":\"minecraft:block/cube_all\"}");
        assertTrue(undefined.problems().stream().anyMatch(p -> p.contains("#down")), undefined.problems().toString());
    }

    @Test
    void straightOnOnlyTheFrontFaceIsVisibleAndFillsTheSquare() {
        var shown = scene(ALL).project(0, 0, 10, 100, 100, ModelScene.Display.NONE);
        assertEquals(1, shown.size());
        assertEquals("south", shown.get(0).face().direction());
        double[] s = shown.get(0).screen();
        assertEquals(20, s[0], 1e-9);  // top left
        assertEquals(20, s[1], 1e-9);
        assertEquals(180, s[4], 1e-9); // bottom right
        assertEquals(180, s[5], 1e-9);
    }

    @Test
    void tiltingShowsTheTopAndTurningShowsASide() {
        var tilted = scene(ALL).project(0, 30, 10, 0, 0, ModelScene.Display.NONE);
        assertEquals(List.of("up", "south"), tilted.stream().map(p -> p.face().direction()).toList(),
                "farthest first: the top is drawn before the front");
        var turned = scene(ALL).project(90, 0, 10, 0, 0, ModelScene.Display.NONE);
        assertEquals(1, turned.size());
        assertEquals("west", turned.get(0).face().direction());
        var isometric = scene(ALL).project(45, 30, 10, 0, 0, ModelScene.Display.NONE);
        assertEquals(3, isometric.size());
    }

    @Test
    void textureMappingMatchesTheCorners() {
        var front = scene(ALL).project(0, 0, 10, 100, 100, ModelScene.Display.NONE).get(0);
        double[] m = ModelScene.textureToScreen(front);
        assertNotNull(m);
        // uv (0,0) is the top left corner of the face, uv (16,16) the bottom right
        assertEquals(20, m[4], 1e-9);
        assertEquals(20, m[5], 1e-9);
        assertEquals(180, m[0] * 16 + m[2] * 16 + m[4], 1e-9);
        assertEquals(180, m[1] * 16 + m[3] * 16 + m[5], 1e-9);
    }

    @Test
    void faceRotationTurnsTheTexture() {
        var rotated = scene("{\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":"
                + "{\"south\":{\"texture\":\"minecraft:block/dirt\",\"rotation\":90}}}]}");
        var plain = scene("{\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":"
                + "{\"south\":{\"texture\":\"minecraft:block/dirt\"}}}]}");
        double[][] a = plain.faces().get(0).uv();
        double[][] b = rotated.faces().get(0).uv();
        assertEquals(a[3][0], b[0][0], 1e-9, "rotating 90 degrees clockwise puts the texture's bottom left at the face's top left");
        assertEquals(a[3][1], b[0][1], 1e-9);
    }

    @Test
    void generatedItemsAreThinSprites() {
        var scene = scene("{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"minecraft:item/diamond\"}}");
        // the generated parent is not loadable here, so the builtin parent ends the chain
        var item = ModelScene.build(json("{\"parent\":\"builtin/generated\",\"textures\":{\"layer0\":\"minecraft:item/diamond\"}}"), id -> null);
        assertTrue(item.flatItem());
        assertEquals(2, item.faces().size());
        assertEquals(1, item.project(0, 0, 4, 0, 0, ModelScene.Display.NONE).size(), "one side is seen from the front");
        assertNull(ModelScene.textureToScreen(new ModelScene.Projected(
                new ModelScene.Face("south", new double[4][3], new double[][]{{0, 0}, {0, 0}, {0, 0}, {0, 0}}, "t", -1),
                new double[8], 0)), "a zero-size texture area cannot be mapped");
        assertTrue(scene.faces().isEmpty() || !scene.problems().isEmpty() || scene.flatItem());
    }
}
