package net.xmilon.craftpy.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A Minecraft block / item model as plain geometry: parents and texture variables are resolved, every element becomes up
 * to six quads, and a quad can be projected to the screen. No game and no UI classes, so the maths is unit-tested.
 * <p>
 * The projection is orthographic, so a quad always lands on the screen as a parallelogram; mapping a texture onto it is
 * therefore an affine transform ({@link #textureToScreen}). This is an approximation of the game's renderer: no lighting,
 * no tint colours, no animated textures.
 */
public final class ModelScene {
    /** One quad: four corners in model space (0..16, vanilla orientation), the matching texture corners, the texture. */
    public record Face(String direction, double[][] corners, double[][] uv, String texture, int tintIndex) {
    }

    /** A face after projection: four screen points (x, y pairs), the depth (larger = nearer) and the viewing side. */
    public record Projected(Face face, double[] screen, double depth) {
    }

    /** Where a model is drawn in a display context ("gui", "ground", "fixed", ...): rotation (degrees), translation (1/16), scale. */
    public record Display(double[] rotation, double[] translation, double[] scale) {
        public static final Display NONE = new Display(new double[3], new double[3], new double[]{1, 1, 1});
    }

    private final List<Face> faces;
    private final Map<String, Display> displays;
    private final List<String> problems;
    private final boolean flatItem;

    private ModelScene(List<Face> faces, Map<String, Display> displays, List<String> problems, boolean flatItem) {
        this.faces = faces;
        this.displays = displays;
        this.problems = problems;
        this.flatItem = flatItem;
    }

    public List<Face> faces() {
        return faces;
    }

    public List<String> problems() {
        return problems;
    }

    /** True for a generated item model (a flat sprite with a little thickness). */
    public boolean flatItem() {
        return flatItem;
    }

    public Display display(String context) {
        return displays.getOrDefault(context, Display.NONE);
    }

    public List<String> displayContexts() {
        return new ArrayList<>(displays.keySet());
    }

    // ------------------------------------------------------------------ building

    /**
     * Builds the scene of a model.
     *
     * @param model  the model JSON
     * @param loader loads a parent by id ({@code "minecraft:block/cube_all"}); returns null when it does not exist
     */
    public static ModelScene build(JsonObject model, Function<String, JsonObject> loader) {
        List<String> problems = new ArrayList<>();
        Map<String, String> textures = new LinkedHashMap<>();
        Map<String, Display> displays = new LinkedHashMap<>();
        JsonArray elements = null;
        boolean generated = false;
        JsonObject current = model;
        int depth = 0;
        while (current != null && depth++ < 16) {
            if (current.has("textures") && current.get("textures").isJsonObject()) {
                for (var entry : current.getAsJsonObject("textures").entrySet()) {
                    textures.putIfAbsent(entry.getKey(), entry.getValue().getAsString());
                }
            }
            if (current.has("display") && current.get("display").isJsonObject()) {
                for (var entry : current.getAsJsonObject("display").entrySet()) {
                    if (entry.getValue().isJsonObject()) {
                        displays.putIfAbsent(entry.getKey(), display(entry.getValue().getAsJsonObject()));
                    }
                }
            }
            if (elements == null && current.has("elements") && current.get("elements").isJsonArray()) {
                elements = current.getAsJsonArray("elements");
            }
            if (!current.has("parent")) {
                break;
            }
            String parent = current.get("parent").getAsString();
            if (parent.endsWith("builtin/generated")) {
                generated = true;
                break;
            }
            if (parent.endsWith("builtin/entity") || parent.endsWith("builtin/missing")) {
                problems.add("The model uses " + parent + ", which has no geometry to show.");
                break;
            }
            JsonObject next = loader.apply(parent);
            if (next == null) {
                problems.add("Parent model " + parent + " was not found.");
                break;
            }
            current = next;
        }
        List<Face> faces = new ArrayList<>();
        if (generated && elements == null) {
            for (int layer = 0; layer < 5; layer++) {
                String ref = resolve("#layer" + layer, textures);
                if (ref != null && !ref.startsWith("#")) {
                    faces.addAll(sprite(ref, layer));
                }
            }
        } else if (elements != null) {
            for (JsonElement element : elements) {
                if (element.isJsonObject()) {
                    element(element.getAsJsonObject(), textures, faces, problems);
                }
            }
        } else if (problems.isEmpty()) {
            problems.add("The model has no elements.");
        }
        return new ModelScene(faces, displays, problems, generated && elements == null);
    }

    // ------------------------------------------------------------------ GeckoLib (.geo.json, Bedrock geometry)

    /** True for a GeckoLib / Bedrock geometry file ({@code {"minecraft:geometry": [...]}}). */
    public static boolean isGeo(JsonObject json) {
        return json.has("minecraft:geometry") && json.get("minecraft:geometry").isJsonArray();
    }

    /**
     * The first geometry of a .geo.json as a scene, every face using {@code texture}. Follows GeckoLib's baking:
     * x is mirrored (origin {@code -(x + size.x)}, pivot {@code -x}, rotation {@code (-x, -y, z)}), box uv per face as in
     * GeckoLib's {@code GeometryQuadUvs.ofBoxUv}, cube then bone then parent bone rotations. The result is centred and
     * scaled into the 0..16 box the preview turns around.
     */
    public static ModelScene geo(JsonObject json, String texture) {
        List<String> problems = new ArrayList<>();
        List<Face> faces = new ArrayList<>();
        JsonArray geometries = json.getAsJsonArray("minecraft:geometry");
        if (geometries.isEmpty() || !geometries.get(0).isJsonObject()) {
            problems.add("The file has no geometry.");
            return new ModelScene(faces, Map.of(), problems, false);
        }
        JsonObject geometry = geometries.get(0).getAsJsonObject();
        JsonObject description = geometry.has("description") && geometry.get("description").isJsonObject()
                ? geometry.getAsJsonObject("description") : new JsonObject();
        double texW = description.has("texture_width") ? description.get("texture_width").getAsDouble() : 64;
        double texH = description.has("texture_height") ? description.get("texture_height").getAsDouble() : 64;
        Map<String, JsonObject> bones = new java.util.LinkedHashMap<>();
        if (geometry.has("bones") && geometry.get("bones").isJsonArray()) {
            for (JsonElement element : geometry.getAsJsonArray("bones")) {
                if (element.isJsonObject() && element.getAsJsonObject().has("name")) {
                    bones.put(element.getAsJsonObject().get("name").getAsString(), element.getAsJsonObject());
                }
            }
        }
        for (JsonObject bone : bones.values()) {
            if (!bone.has("cubes") || !bone.get("cubes").isJsonArray()) {
                continue;
            }
            for (JsonElement element : bone.getAsJsonArray("cubes")) {
                if (element.isJsonObject()) {
                    geoCube(element.getAsJsonObject(), bone, bones, texW, texH, texture, faces);
                }
            }
        }
        if (faces.isEmpty()) {
            problems.add("The geometry has no cubes.");
        }
        return new ModelScene(fitInto16(faces), Map.of(), problems, false);
    }

    private static void geoCube(JsonObject cube, JsonObject bone, Map<String, JsonObject> bones, double texW, double texH,
                                String texture, List<Face> out) {
        double[] origin = vec(cube, "origin", 0);
        double[] size = vec(cube, "size", 0);
        double inflate = cube.has("inflate") ? cube.get("inflate").getAsDouble() : 0;
        boolean mirror = cube.has("mirror") ? cube.get("mirror").getAsBoolean()
                : bone.has("mirror") && bone.get("mirror").getAsBoolean();
        double[] from = {-(origin[0] + size[0]) - inflate, origin[1] - inflate, origin[2] - inflate};
        double[] to = {-origin[0] + inflate, origin[1] + size[1] + inflate, origin[2] + size[2] + inflate};
        JsonElement uvJson = cube.get("uv");
        for (String direction : new String[]{"north", "south", "east", "west", "up", "down"}) {
            double[] rect = geoUv(uvJson, direction, size, mirror);
            if (rect == null) {
                continue;
            }
            double[][] corner = corners(direction, from, to);
            double u0 = rect[0] * 16 / texW, v0 = rect[1] * 16 / texH, u1 = rect[2] * 16 / texW, v1 = rect[3] * 16 / texH;
            double[][] tex = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
            if (cube.has("rotation")) {
                rotateXyz(corner, mirroredPivot(cube, from, to), vec(cube, "rotation", 0));
            }
            // the bone and every parent bone above it
            JsonObject current = bone;
            for (int depth = 0; current != null && depth < 64; depth++) {
                if (current.has("rotation")) {
                    rotateXyz(corner, mirroredPivot(current, null, null), vec(current, "rotation", 0));
                }
                current = current.has("parent") ? bones.get(current.get("parent").getAsString()) : null;
            }
            out.add(new Face(direction, corner, tex, texture, -1));
        }
    }

    /** The pivot with x mirrored; a cube without one turns around its centre. */
    private static double[] mirroredPivot(JsonObject json, double[] from, double[] to) {
        if (json.has("pivot")) {
            double[] p = vec(json, "pivot", 0);
            return new double[]{-p[0], p[1], p[2]};
        }
        return from == null ? new double[3] : new double[]{(from[0] + to[0]) / 2, (from[1] + to[1]) / 2, (from[2] + to[2]) / 2};
    }

    /** Texture rectangle {u0, v0, u1, v1} in texture pixels, or null when the face has none. */
    static double[] geoUv(JsonElement uv, String direction, double[] size, boolean mirror) {
        double[] rect;
        if (uv != null && uv.isJsonArray() && uv.getAsJsonArray().size() >= 2) {
            double u = uv.getAsJsonArray().get(0).getAsDouble();
            double v = uv.getAsJsonArray().get(1).getAsDouble();
            double x = Math.floor(size[0]), y = Math.floor(size[1]), z = Math.floor(size[2]);
            rect = switch (direction) {
                case "west" -> new double[]{u + z + x, v + z, u + z + x + z, v + z + y};
                case "east" -> new double[]{u, v + z, u + z, v + z + y};
                case "north" -> new double[]{u + z, v + z, u + z + x, v + z + y};
                case "south" -> new double[]{u + z + x + z, v + z, u + z + x + z + x, v + z + y};
                case "up" -> new double[]{u + z, v, u + z + x, v + z};
                default -> new double[]{u + z + x, v + z, u + z + x + x, v}; // down: flipped, GeckoLib's -size.z
            };
        } else if (uv != null && uv.isJsonObject() && uv.getAsJsonObject().has(direction)
                && uv.getAsJsonObject().get(direction).isJsonObject()) {
            JsonObject face = uv.getAsJsonObject().getAsJsonObject(direction);
            double[] at = vec(face, "uv", 0);
            double[] span = vec(face, "uv_size", 0);
            rect = new double[]{at[0], at[1], at[0] + span[0], at[1] + span[1]};
        } else {
            return null;
        }
        if (mirror) {
            double swap = rect[0];
            rect[0] = rect[2];
            rect[2] = swap;
        }
        return rect;
    }

    /** Bedrock order: x first, then y, then z, all around {@code pivot}; x and y are mirrored like the cube. */
    private static void rotateXyz(double[][] corners, double[] pivot, double[] degrees) {
        double ax = Math.toRadians(-degrees[0]), ay = Math.toRadians(-degrees[1]), az = Math.toRadians(degrees[2]);
        for (double[] c : corners) {
            double x = c[0] - pivot[0], y = c[1] - pivot[1], z = c[2] - pivot[2];
            double y1 = y * Math.cos(ax) - z * Math.sin(ax), z1 = y * Math.sin(ax) + z * Math.cos(ax);
            double x2 = x * Math.cos(ay) + z1 * Math.sin(ay), z2 = -x * Math.sin(ay) + z1 * Math.cos(ay);
            double x3 = x2 * Math.cos(az) - y1 * Math.sin(az), y3 = x2 * Math.sin(az) + y1 * Math.cos(az);
            c[0] = x3 + pivot[0];
            c[1] = y3 + pivot[1];
            c[2] = z2 + pivot[2];
        }
    }

    /** Moves and scales faces so their bounds sit centred in the 0..16 box (mobs are much larger than a block). */
    private static List<Face> fitInto16(List<Face> faces) {
        if (faces.isEmpty()) {
            return faces;
        }
        double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (Face f : faces) {
            for (double[] c : f.corners()) {
                for (int i = 0; i < 3; i++) {
                    min[i] = Math.min(min[i], c[i]);
                    max[i] = Math.max(max[i], c[i]);
                }
            }
        }
        double extent = Math.max(max[0] - min[0], Math.max(max[1] - min[1], max[2] - min[2]));
        double scale = extent > 0 ? 16 / extent : 1;
        for (Face f : faces) {
            for (double[] c : f.corners()) {
                for (int i = 0; i < 3; i++) {
                    c[i] = 8 + (c[i] - (min[i] + max[i]) / 2) * scale;
                }
            }
        }
        return faces;
    }

    private static Display display(JsonObject json) {
        return new Display(vec(json, "rotation", 0), vec(json, "translation", 0), vec(json, "scale", 1));
    }

    private static double[] vec(JsonObject json, String key, double fallback) {
        double[] out = {fallback, fallback, fallback};
        if (json.has(key) && json.get(key).isJsonArray()) {
            JsonArray array = json.getAsJsonArray(key);
            for (int i = 0; i < 3 && i < array.size(); i++) {
                out[i] = array.get(i).getAsDouble();
            }
        }
        return out;
    }

    /** "#all" to the real texture id; null when the variable is missing, loops are cut after 16 steps. */
    static String resolve(String ref, Map<String, String> textures) {
        String value = ref;
        for (int i = 0; i < 16 && value != null && value.startsWith("#"); i++) {
            value = textures.get(value.substring(1));
        }
        return value;
    }

    /** A generated item layer: a 16x16 sprite one pixel thick, front and back. */
    private static List<Face> sprite(String texture, int layer) {
        double z0 = 7.5 - layer * 0.01;
        double z1 = 8.5 + layer * 0.01;
        double[][] full = {{0, 0}, {16, 0}, {16, 16}, {0, 16}};
        List<Face> out = new ArrayList<>();
        out.add(new Face("north", new double[][]{{16, 16, z0}, {0, 16, z0}, {0, 0, z0}, {16, 0, z0}}, full, texture, layer));
        out.add(new Face("south", new double[][]{{0, 16, z1}, {16, 16, z1}, {16, 0, z1}, {0, 0, z1}}, full, texture, layer));
        return out;
    }

    private static void element(JsonObject element, Map<String, String> textures, List<Face> out, List<String> problems) {
        double[] from = vec(element, "from", 0);
        double[] to = vec(element, "to", 16);
        JsonObject faces = element.has("faces") && element.get("faces").isJsonObject() ? element.getAsJsonObject("faces") : null;
        if (faces == null) {
            return;
        }
        for (var entry : faces.entrySet()) {
            String direction = entry.getKey();
            if (!entry.getValue().isJsonObject() || corners(direction, from, to) == null) {
                continue;
            }
            JsonObject face = entry.getValue().getAsJsonObject();
            String texture = face.has("texture") ? resolve(face.get("texture").getAsString(), textures) : null;
            if (texture == null || texture.startsWith("#")) {
                problems.add("Texture variable " + (face.has("texture") ? face.get("texture").getAsString() : "?")
                        + " of the " + direction + " face is not defined.");
                continue;
            }
            double[] uv = defaultUv(direction, from, to);
            if (face.has("uv") && face.get("uv").isJsonArray() && face.getAsJsonArray("uv").size() >= 4) {
                for (int i = 0; i < 4; i++) {
                    uv[i] = face.getAsJsonArray("uv").get(i).getAsDouble();
                }
            }
            int rotation = face.has("rotation") ? Math.floorMod(face.get("rotation").getAsInt() / 90, 4) : 0;
            double[][] corner = corners(direction, from, to);
            double[][] texCorner = {{uv[0], uv[1]}, {uv[2], uv[1]}, {uv[2], uv[3]}, {uv[0], uv[3]}};
            double[][] rotated = new double[4][];
            for (int k = 0; k < 4; k++) {
                rotated[k] = texCorner[Math.floorMod(k - rotation, 4)];
            }
            if (element.has("rotation") && element.get("rotation").isJsonObject()) {
                rotateElement(corner, element.getAsJsonObject("rotation"));
            }
            out.add(new Face(direction, corner, rotated, texture, face.has("tintindex") ? face.get("tintindex").getAsInt() : -1));
        }
    }

    /** Quad corners for a direction, clockwise as seen from outside, starting at the top left. */
    static double[][] corners(String direction, double[] f, double[] t) {
        double x1 = f[0], y1 = f[1], z1 = f[2], x2 = t[0], y2 = t[1], z2 = t[2];
        return switch (direction) {
            case "north" -> new double[][]{{x2, y2, z1}, {x1, y2, z1}, {x1, y1, z1}, {x2, y1, z1}};
            case "south" -> new double[][]{{x1, y2, z2}, {x2, y2, z2}, {x2, y1, z2}, {x1, y1, z2}};
            case "east" -> new double[][]{{x2, y2, z2}, {x2, y2, z1}, {x2, y1, z1}, {x2, y1, z2}};
            case "west" -> new double[][]{{x1, y2, z1}, {x1, y2, z2}, {x1, y1, z2}, {x1, y1, z1}};
            case "up" -> new double[][]{{x1, y2, z1}, {x2, y2, z1}, {x2, y2, z2}, {x1, y2, z2}};
            case "down" -> new double[][]{{x1, y1, z2}, {x2, y1, z2}, {x2, y1, z1}, {x1, y1, z1}};
            default -> null;
        };
    }

    /** The texture area a face shows when the model gives no uv (vanilla's rule). */
    static double[] defaultUv(String direction, double[] f, double[] t) {
        double x1 = f[0], y1 = f[1], z1 = f[2], x2 = t[0], y2 = t[1], z2 = t[2];
        return switch (direction) {
            case "up" -> new double[]{x1, z1, x2, z2};
            case "down" -> new double[]{x1, 16 - z2, x2, 16 - z1};
            case "north" -> new double[]{16 - x2, 16 - y2, 16 - x1, 16 - y1};
            case "south" -> new double[]{x1, 16 - y2, x2, 16 - y1};
            case "west" -> new double[]{z1, 16 - y2, z2, 16 - y1};
            default -> new double[]{16 - z2, 16 - y2, 16 - z1, 16 - y1};
        };
    }

    private static void rotateElement(double[][] corners, JsonObject rotation) {
        double[] origin = vec(rotation, "origin", 8);
        double angle = Math.toRadians(rotation.has("angle") ? rotation.get("angle").getAsDouble() : 0);
        String axis = rotation.has("axis") ? rotation.get("axis").getAsString() : "y";
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        for (double[] c : corners) {
            double x = c[0] - origin[0];
            double y = c[1] - origin[1];
            double z = c[2] - origin[2];
            double nx = x, ny = y, nz = z;
            switch (axis) {
                case "x" -> {
                    ny = y * cos - z * sin;
                    nz = y * sin + z * cos;
                }
                case "z" -> {
                    nx = x * cos - y * sin;
                    ny = x * sin + y * cos;
                }
                default -> {
                    nx = x * cos + z * sin;
                    nz = -x * sin + z * cos;
                }
            }
            c[0] = nx + origin[0];
            c[1] = ny + origin[1];
            c[2] = nz + origin[2];
        }
    }

    // ------------------------------------------------------------------ projection

    /**
     * Projects the visible faces. The model is centred, optionally put through a display transform, turned by the
     * orbit angles and drawn with an orthographic camera looking along -z. Faces turned away are dropped; the rest come
     * back farthest first (painter's order).
     *
     * @param yaw      turn around the vertical axis, degrees
     * @param pitch    tilt, degrees (positive looks from above)
     * @param scale    pixels per model unit
     * @param display  display transform, or {@link Display#NONE}
     */
    public List<Projected> project(double yaw, double pitch, double scale, double centerX, double centerY, Display display) {
        double[][] model = matrixXyz(display.rotation());
        double cy = Math.cos(Math.toRadians(yaw));
        double sy = Math.sin(Math.toRadians(yaw));
        double cp = Math.cos(Math.toRadians(pitch));
        double sp = Math.sin(Math.toRadians(pitch));
        List<Projected> out = new ArrayList<>();
        for (Face face : faces) {
            double[][] world = new double[4][];
            for (int k = 0; k < 4; k++) {
                double[] c = face.corners()[k];
                double x = (c[0] - 8) * display.scale()[0];
                double y = (c[1] - 8) * display.scale()[1];
                double z = (c[2] - 8) * display.scale()[2];
                double[] r = mul(model, x, y, z);
                r[0] += display.translation()[0];
                r[1] += display.translation()[1];
                r[2] += display.translation()[2];
                // orbit: yaw around y, then pitch around x
                double ox = r[0] * cy + r[2] * sy;
                double oz = -r[0] * sy + r[2] * cy;
                double oy = r[1] * cp - oz * sp;
                double oz2 = r[1] * sp + oz * cp;
                world[k] = new double[]{ox, oy, oz2};
            }
            // corners run clockwise seen from outside, so the outward normal is the negative of this cross product
            double[] normal = cross(sub(world[1], world[0]), sub(world[3], world[0]));
            if (normal[2] >= -1e-9) {
                continue; // facing away
            }
            double[] screen = new double[8];
            double z = 0;
            for (int k = 0; k < 4; k++) {
                screen[k * 2] = centerX + world[k][0] * scale;
                screen[k * 2 + 1] = centerY - world[k][1] * scale;
                z += world[k][2] / 4;
            }
            out.add(new Projected(face, screen, z));
        }
        out.sort((a, b) -> Double.compare(a.depth(), b.depth()));
        return out;
    }

    private static double[][] matrixXyz(double[] degrees) {
        double[][] x = rotX(Math.toRadians(degrees[0]));
        double[][] y = rotY(Math.toRadians(degrees[1]));
        double[][] z = rotZ(Math.toRadians(degrees[2]));
        return mul(x, mul(y, z));
    }

    private static double[][] rotX(double a) {
        return new double[][]{{1, 0, 0}, {0, Math.cos(a), -Math.sin(a)}, {0, Math.sin(a), Math.cos(a)}};
    }

    private static double[][] rotY(double a) {
        return new double[][]{{Math.cos(a), 0, Math.sin(a)}, {0, 1, 0}, {-Math.sin(a), 0, Math.cos(a)}};
    }

    private static double[][] rotZ(double a) {
        return new double[][]{{Math.cos(a), -Math.sin(a), 0}, {Math.sin(a), Math.cos(a), 0}, {0, 0, 1}};
    }

    private static double[][] mul(double[][] a, double[][] b) {
        double[][] out = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k < 3; k++) {
                    out[i][j] += a[i][k] * b[k][j];
                }
            }
        }
        return out;
    }

    private static double[] mul(double[][] m, double x, double y, double z) {
        return new double[]{m[0][0] * x + m[0][1] * y + m[0][2] * z, m[1][0] * x + m[1][1] * y + m[1][2] * z,
                m[2][0] * x + m[2][1] * y + m[2][2] * z};
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    /**
     * The affine map from texture space (uv in 0..16) to the screen for one projected face, as
     * {@code {a, b, c, d, tx, ty}} with {@code x' = a*u + c*v + tx} and {@code y' = b*u + d*v + ty}. Solved from three
     * corners, so face rotation and flipped uv come out right.
     */
    public static double[] textureToScreen(Projected p) {
        double[][] t = p.face().uv();
        double[] s = p.screen();
        double du1 = t[1][0] - t[0][0], dv1 = t[1][1] - t[0][1];
        double du3 = t[3][0] - t[0][0], dv3 = t[3][1] - t[0][1];
        double det = du1 * dv3 - du3 * dv1;
        if (Math.abs(det) < 1e-9) {
            return null; // the face shows a zero-size piece of the texture
        }
        double dx1 = s[2] - s[0], dy1 = s[3] - s[1];
        double dx3 = s[6] - s[0], dy3 = s[7] - s[1];
        double a = (dx1 * dv3 - dx3 * dv1) / det;
        double c = (dx3 * du1 - dx1 * du3) / det;
        double b = (dy1 * dv3 - dy3 * dv1) / det;
        double d = (dy3 * du1 - dy1 * du3) / det;
        double tx = s[0] - a * t[0][0] - c * t[0][1];
        double ty = s[1] - b * t[0][0] - d * t[0][1];
        return new double[]{a, b, c, d, tx, ty};
    }

    /** Every texture id the model asks for, to load them before drawing. */
    public List<String> textures() {
        List<String> out = new ArrayList<>();
        Map<String, Boolean> seen = new HashMap<>();
        for (Face face : faces) {
            if (seen.put(face.texture(), true) == null) {
                out.add(face.texture());
            }
        }
        return out;
    }
}
