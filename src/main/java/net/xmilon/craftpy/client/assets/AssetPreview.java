package net.xmilon.craftpy.client.assets;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.RandomSource;

/**
 * Shared drawing + resolution helpers for the asset bank thumbnails and the
 * asset viewers. Sounds get a speaker glyph, models get a resolved texture
 * thumbnail (falling back to a cube glyph). All resource lookups go through the
 * active resource manager so project packs are included.
 */
public final class AssetPreview {
    private static final ResourceLocation NONE = ResourceLocation.withDefaultNamespace("__craftpy_none__");
    private static final Map<String, ResourceLocation> MODEL_TEX_CACHE = new HashMap<>();
    private static final Map<String, String> MODEL_JSON_CACHE = new HashMap<>();

    private AssetPreview() {
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    // ------------------------------------------------------------------
    // Icons (drawn with fills so no texture asset is required)
    // ------------------------------------------------------------------

    /** A small speaker-with-waves "volume" glyph, fits inside (x,y,size,size). */
    public static void drawSpeaker(GuiGraphics g, int x, int y, int size) {
        int col = 0xFFEAF0F6;
        int bodyX = x + (int) (size * 0.16);
        int bodyY = y + (int) (size * 0.38);
        int bodyW = Math.max(2, (int) (size * 0.14));
        int bodyH = Math.max(4, (int) (size * 0.24));
        g.fill(bodyX, bodyY, bodyX + bodyW, bodyY + bodyH, col);

        int coneEnd = x + (int) (size * 0.50);
        int top = y + (int) (size * 0.20);
        int bot = y + (int) (size * 0.80);
        int steps = Math.max(1, coneEnd - (bodyX + bodyW));
        for (int i = 0; i < steps; i++) {
            int px = bodyX + bodyW + i;
            float f = steps <= 1 ? 0f : (float) i / (steps - 1);
            int cy0 = (int) (bodyY + (top - bodyY) * f);
            int cy1 = (int) (bodyY + bodyH + (bot - (bodyY + bodyH)) * f);
            g.fill(px, cy0, px + 1, cy1, col);
        }

        int wx = coneEnd + Math.max(2, (int) (size * 0.08));
        g.fill(wx, y + (int) (size * 0.34), wx + 1, y + (int) (size * 0.66), col);
        g.fill(wx + 3, y + (int) (size * 0.24), wx + 4, y + (int) (size * 0.76), col);
    }

    /** A simple isometric cube glyph, fits inside (x,y,size,size). */
    public static void drawCube(GuiGraphics g, int x, int y, int size) {
        int light = 0xFFCBD5E1;
        int mid = 0xFF9AA7B4;
        int dark = 0xFF6B7683;
        int leftX0 = x + (int) (size * 0.18);
        int midX = x + (int) (size * 0.48);
        int rightX1 = x + (int) (size * 0.82);
        int topY0 = y + (int) (size * 0.20);
        int midY = y + (int) (size * 0.38);
        int botY = y + (int) (size * 0.82);
        g.fill(leftX0 + (int) (size * 0.12), topY0, rightX1 - (int) (size * 0.04), midY, light);
        g.fill(leftX0, midY, midX, botY, mid);
        g.fill(midX, midY, rightX1, botY, dark);
    }

    // ------------------------------------------------------------------
    // Model resolution
    // ------------------------------------------------------------------

    /** The raw model JSON text, or "" if it could not be loaded. */
    public static String modelJson(String id) {
        String cached = MODEL_JSON_CACHE.get(id);
        if (cached != null) {
            return cached;
        }
        // a miss is not remembered: the file may appear with the next pack reload
        ResourceLocation rl = modelLocation(id);
        if (rl == null) {
            return "";
        }
        Optional<Resource> res = GameResources.manager().getResource(rl);
        if (res.isEmpty()) {
            return "";
        }
        try (InputStream in = res.get().open()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            MODEL_JSON_CACHE.put(id, text);
            return text;
        } catch (Exception e) {
            return "";
        }
    }

    /** A texture ResourceLocation suitable for a model thumbnail, or null. */
    public static ResourceLocation modelPreviewTexture(String id) {
        ResourceLocation cached = MODEL_TEX_CACHE.computeIfAbsent(id, key -> {
            ResourceLocation found = resolveModelTexture(key, 0);
            return found == null ? NONE : found;
        });
        return cached == NONE ? null : cached;
    }

    private static ResourceLocation resolveModelTexture(String id, int depth) {
        if (depth > 4) {
            return null;
        }
        JsonObject model = parseModel(modelJson(id));
        if (model == null) {
            return null;
        }
        if (model.has("textures") && model.get("textures").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : model.getAsJsonObject("textures").entrySet()) {
                JsonElement value = entry.getValue();
                if (value == null || !value.isJsonPrimitive()) {
                    continue;
                }
                String ref = value.getAsString();
                if (ref.startsWith("#")) {
                    continue;
                }
                ResourceLocation rl = textureResource(ref);
                if (rl != null && GameResources.manager().getResource(rl).isPresent()) {
                    return rl;
                }
            }
        }
        if (model.has("parent") && model.get("parent").isJsonPrimitive()) {
            return resolveModelTexture(model.get("parent").getAsString(), depth + 1);
        }
        return null;
    }

    private static JsonObject parseModel(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(json);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static ResourceLocation modelLocation(String id) {
        try {
            String ns = id.contains(":") ? id.substring(0, id.indexOf(':')) : "minecraft";
            String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            return ResourceLocation.fromNamespaceAndPath(ns, "models/" + path + ".json");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static ResourceLocation textureResource(String ref) {
        try {
            String value = ref.replace(".png", "");
            String ns = value.contains(":") ? value.substring(0, value.indexOf(':')) : "minecraft";
            String path = value.contains(":") ? value.substring(value.indexOf(':') + 1) : value;
            if (path.startsWith("textures/")) {
                path = path.substring("textures/".length());
            }
            return ResourceLocation.fromNamespaceAndPath(ns, "textures/" + path + ".png");
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Sound resolution
    // ------------------------------------------------------------------

    /**
     * The physical .ogg resource for a sound event id, or null if unresolved. An event with several variants (stone
     * breaking has four) always gives the same one, so copying it twice copies the same file.
     */
    public static ResourceLocation soundFile(String id) {
        try {
            SoundManager manager = mc().getSoundManager();
            ResourceLocation rl = ResourceLocation.parse(id);
            WeighedSoundEvents events = manager.getSoundEvent(rl);
            if (events == null) {
                return null;
            }
            Sound sound = events.getSound(RandomSource.create(0L));
            if (sound == null) {
                return null;
            }
            ResourceLocation path = sound.getPath();
            if (path == null || GameResources.manager().getResource(path).isEmpty()) {
                return null;
            }
            return path;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Reads the raw bytes of a resource, or null. */
    public static byte[] readResource(ResourceLocation rl) {
        if (rl == null) {
            return null;
        }
        Optional<Resource> res = GameResources.manager().getResource(rl);
        if (res.isEmpty()) {
            return null;
        }
        try (InputStream in = res.get().open()) {
            return in.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    /** Clears caches so a resource reload re-resolves previews. */
    public static void invalidate() {
        MODEL_TEX_CACHE.clear();
        MODEL_JSON_CACHE.clear();
    }
}
