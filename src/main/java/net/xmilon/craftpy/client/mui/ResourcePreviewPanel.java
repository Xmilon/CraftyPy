package net.xmilon.craftpy.client.mui;

import net.xmilon.craftpy.client.IdeHost;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import icyllis.modernui.R;
import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.Canvas;
import icyllis.modernui.graphics.Matrix;
import icyllis.modernui.graphics.Paint;
import icyllis.modernui.graphics.drawable.ColorDrawable;
import icyllis.modernui.view.MotionEvent;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.FrameLayout;
import icyllis.modernui.widget.HorizontalScrollView;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.TextView;
import net.xmilon.craftpy.client.ModelScene;
import net.xmilon.craftpy.client.PixelBuffer;
import net.xmilon.craftpy.client.assets.ModelResources;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static net.xmilon.craftpy.client.mui.CraftPyFragment.*;

/**
 * The resource preview tab of the asset dock: models and block states as a 3D picture you can turn (orthographic, drawn
 * with Modern UI; an approximation of the game's own renderer - no lighting, tint colours or animation), the display
 * transforms of a model (gui, ground, fixed, hands, head), and a flat view of textures. Resources are read on the client
 * thread, the picture is drawn on the UI thread. In game, blocks, items and mobs are drawn live by the game's own
 * renderers inside the tab instead ({@link McCanvas}, {@link McPainters}): the real look of vanilla and mod models,
 * GeckoLib included, turned by dragging.
 */
final class ResourcePreviewPanel implements DockPanel {
    private final Context context;
    private final LinearLayout root;
    private final TextView title;
    private final TextView info;
    private final LinearLayout presets;
    private final FrameLayout stage;
    private final ModelView modelView;
    private final PixelCanvas flat;
    private final McCanvas live;
    private int generation;
    private static final String HINT = "Double click a texture, use Preview in the explorer menu on a model or block state,"
            + " or open a block or item in the browser tab. Mobs shows any mob of the game and its mods.";

    ResourcePreviewPanel(Context context) {
        this.context = context;
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        title = label(context, "Nothing to preview", R.attr.textAppearanceTitleSmall);
        title.setPadding(root.dp(4), root.dp(4), root.dp(4), root.dp(2));
        var header = row(context);
        header.addView(title, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        header.addView(chip("Mobs...", v -> pickMob()));
        header.addView(chip("Clear", v -> clear()));
        root.addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        presets = row(context);
        var scroll = new HorizontalScrollView(context);
        scroll.addView(presets);
        root.addView(scroll);

        stage = new FrameLayout(context);
        stage.setBackground(new ColorDrawable(0xFF202020));
        modelView = new ModelView(context);
        flat = new PixelCanvas(context, new PixelBuffer(1, 1));
        flat.setTool(PixelCanvas.Tool.PICKER);
        stage.addView(modelView, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        stage.addView(flat, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        live = new McCanvas(context, null);
        live.setVisibility(View.GONE);
        stage.addView(live, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        var stageLp = new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1);
        stageLp.topMargin = root.dp(4);
        root.addView(stage, stageLp);

        info = label(context, HINT, R.attr.textAppearanceBodySmall);
        info.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
        info.setPadding(root.dp(4), root.dp(4), root.dp(4), root.dp(4));
        root.addView(info, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        modelView.setVisibility(View.GONE);
        flat.setVisibility(View.GONE);
    }

    @Override
    public View view() {
        return root;
    }

    @Override
    public boolean dirty() {
        return false;
    }

    @Override
    public boolean back() {
        return false;
    }

    // ------------------------------------------------------------------ showing things

    /** Back to the empty panel. */
    void clear() {
        showMessage("Nothing to preview", HINT);
        modelView.set(null, Map.of());
        flat.setBuffer(new PixelBuffer(1, 1));
    }

    private void hideLive() {
        live.setPainter(null);
        live.setVisibility(View.GONE);
    }

    void showMessage(String name, String text) {
        generation++;
        title.setText(name);
        info.setText(text);
        modelView.setVisibility(View.GONE);
        flat.setVisibility(View.GONE);
        hideLive();
        presets.removeAllViews();
    }

    /** A texture as a flat picture (read-only: the picker tool only reads colours). */
    void showTexture(String name, PixelBuffer texture) {
        generation++;
        title.setText(name);
        presets.removeAllViews();
        modelView.setVisibility(View.GONE);
        hideLive();
        flat.setVisibility(View.VISIBLE);
        flat.setBuffer(texture);
        info.setText(texture.width() + "x" + texture.height() + ", " + texture.palette(4096).size() + " colours");
    }

    /** True when the game can draw blocks, items and mobs in the tab (in game, in a world). */
    static boolean canRender() {
        return McCanvas.live() && net.minecraft.client.Minecraft.getInstance().level != null;
    }

    /** A block, item or mob drawn live by the game itself (in game only; see {@link #canRender}). */
    void showRender(String name, McPainters.Kind kind, String id) {
        final int mine = ++generation;
        title.setText(name);
        presets.removeAllViews();
        flat.setVisibility(View.GONE);
        modelView.setVisibility(View.GONE);
        live.setVisibility(View.VISIBLE);
        info.setText("Drawn by Minecraft. Drag to turn, wheel to zoom.");
        live.setPainter(McPainters.turntable(kind, id, problem -> root.post(() -> {
            if (mine == generation) {
                info.setText("The game could not draw " + id + ": " + problem);
            }
        })));
    }

    private void pickMob() {
        if (!canRender()) {
            showMessage("Mobs", "Mobs are drawn by the game: join a world first (the standalone IDE has no renderer).");
            return;
        }
        var dialog = ListDialog.open(root, "Mobs of the game and its mods", true);
        List<ListDialog.Row> rows = new java.util.ArrayList<>();
        for (String id : McPainters.mobIds()) {
            rows.add(ListDialog.Row.link(id, "", () -> {
                dialog.dismiss();
                showRender(id, McPainters.Kind.MOB, id);
            }));
        }
        dialog.setMessage(rows.size() + " mob types").setRows(rows);
    }

    /** A model or block state from its JSON text (the unsaved editor text is fine). */
    void showModel(String name, String json) {
        final int mine = ++generation;
        title.setText(name);
        JsonObject parsed;
        try {
            JsonElement element = JsonParser.parseString(json);
            parsed = element.getAsJsonObject();
        } catch (RuntimeException e) {
            showMessage(name, "This is not valid JSON yet: " + e.getMessage());
            return;
        }
        info.setText("Loading...");
        IdeHost.get().execute(() -> {
            try {
                buildModel(mine, name, parsed);
            } catch (RuntimeException e) { // a model that cannot be drawn must not leave the panel on "Loading..."
                root.post(() -> {
                    if (mine == generation) {
                        showMessage(name, "Could not draw this model: " + e.getMessage());
                    }
                });
            }
        });
    }

    /** Client thread: resolves the model and its textures, then hands the scene to the view. */
    private void buildModel(int mine, String name, JsonObject parsed) {
        ModelResources resources = new ModelResources();
        ModelScene scene = ModelScene.isGeo(parsed) ? ModelScene.geo(parsed, geoTexture(name, resources))
                : ModelScene.build(blockStateModel(parsed), ModelResources::model);
        Map<String, PixelBuffer> textures = new HashMap<>();
        for (String id : scene.textures()) {
            textures.put(id, resources.texture(id));
        }
        StringBuilder report = new StringBuilder();
        report.append(scene.faces().size()).append(" faces, ").append(textures.size()).append(" textures");
        for (String id : scene.textures()) {
            if (!resources.found(id)) {
                report.append("\nTexture not found: ").append(id)
                        .append(" (run the project so its assets are in the pack)");
            }
        }
        for (String problem : scene.problems()) {
            report.append('\n').append(problem);
        }
        root.post(() -> {
            if (mine != generation) {
                return; // a newer file was opened meanwhile
            }
            flat.setVisibility(View.GONE);
            hideLive();
            modelView.setVisibility(View.VISIBLE);
            modelView.set(scene, textures);
            info.setText(report.toString());
            rebuildPresets(scene);
        });
    }

    /**
     * The texture of a GeckoLib model: GeckoLib keeps {@code geo/<path>.geo.json} next to {@code textures/<path>.png},
     * mods also use {@code textures/entity|item|armor/<name>.png}. The first that exists, else the first guess.
     */
    static String geoTexture(String name, ModelResources resources) {
        String clean = name.replace('\\', '/');
        String namespace = "minecraft";
        int colon = clean.indexOf(':');
        if (colon > 0) {
            namespace = clean.substring(0, colon);
            clean = clean.substring(colon + 1);
        } else {
            String project = net.xmilon.craftpy.client.ClientProjectStore.getInstance().activeProjectId();
            if (project != null) {
                namespace = project;
            }
        }
        int geo = clean.indexOf("geo/");
        String path = (geo >= 0 ? clean.substring(geo + 4) : clean.substring(clean.lastIndexOf('/') + 1)).replace(".geo.json", "");
        String base = path.substring(path.lastIndexOf('/') + 1);
        List<String> guesses = List.of(path, "entity/" + base, "item/" + base, "armor/" + base, "block/" + base, base);
        for (String guess : guesses) {
            String id = namespace + ":" + guess;
            resources.texture(id);
            if (resources.found(id)) {
                return id;
            }
        }
        return namespace + ":" + guesses.get(0);
    }

    /** A block state file points at models; the first one found is shown. A model file is returned unchanged. */
    static JsonObject blockStateModel(JsonObject json) {
        if (json.has("variants") && json.get("variants").isJsonObject()) {
            for (var entry : json.getAsJsonObject("variants").entrySet()) {
                String model = modelOf(entry.getValue());
                if (model != null) {
                    JsonObject wrapper = new JsonObject();
                    wrapper.addProperty("parent", model);
                    return wrapper;
                }
            }
        }
        if (json.has("multipart") && json.get("multipart").isJsonArray()) {
            for (JsonElement part : json.getAsJsonArray("multipart")) {
                if (part.isJsonObject() && part.getAsJsonObject().has("apply")) {
                    String model = modelOf(part.getAsJsonObject().get("apply"));
                    if (model != null) {
                        JsonObject wrapper = new JsonObject();
                        wrapper.addProperty("parent", model);
                        return wrapper;
                    }
                }
            }
        }
        return json;
    }

    private static String modelOf(JsonElement variant) {
        JsonElement single = variant.isJsonArray() && !variant.getAsJsonArray().isEmpty() ? variant.getAsJsonArray().get(0) : variant;
        return single.isJsonObject() && single.getAsJsonObject().has("model") ? single.getAsJsonObject().get("model").getAsString() : null;
    }

    private void rebuildPresets(ModelScene scene) {
        presets.removeAllViews();
        presets.addView(chip("Free", v -> modelView.useDisplay("", scene)));
        for (String context : List.of("gui", "ground", "fixed", "thirdperson_righthand", "firstperson_righthand", "head")) {
            if (scene.displayContexts().contains(context)) {
                presets.addView(chip(context.replace("_righthand", ""), v -> modelView.useDisplay(context, scene)));
            }
        }
    }

    private TextView chip(String text, View.OnClickListener onClick) {
        var chip = label(context, text, R.attr.textAppearanceLabelMedium);
        chip.setPadding(chip.dp(8), chip.dp(4), chip.dp(8), chip.dp(4));
        chip.setClickable(true);
        hoverBackground(chip);
        chip.setOnClickListener(onClick);
        return chip;
    }

    // ------------------------------------------------------------------ the 3D picture

    private static final class ModelView extends View {
        private final Paint paint = new Paint();
        private ModelScene scene;
        private Map<String, PixelBuffer> textures = Map.of();
        private ModelScene.Display display = ModelScene.Display.NONE;
        private double yaw = 35;
        private double pitch = 25;
        private double zoom = 1;
        private float lastX;
        private float lastY;

        ModelView(Context context) {
            super(context);
        }

        void set(ModelScene scene, Map<String, PixelBuffer> textures) {
            this.scene = scene;
            this.textures = textures;
            zoom = 1;
            useDisplay("", scene);
        }

        void useDisplay(String context, ModelScene scene) {
            display = context.isEmpty() ? ModelScene.Display.NONE : scene.display(context);
            yaw = context.isEmpty() ? 35 : 0;
            pitch = context.isEmpty() ? 25 : 0;
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            if (scene == null) {
                return;
            }
            double scale = Math.min(getWidth(), getHeight()) / 28.0 * zoom;
            List<ModelScene.Projected> faces = scene.project(yaw, pitch, scale, getWidth() / 2.0, getHeight() / 2.0, display);
            int budget = 60_000;
            for (ModelScene.Projected face : faces) {
                double[] m = ModelScene.textureToScreen(face);
                PixelBuffer texture = textures.get(face.face().texture());
                if (m == null || texture == null) {
                    continue;
                }
                canvas.save();
                canvas.concat(new Matrix((float) m[0], (float) m[1], 0, (float) m[2], (float) m[3], 0,
                        (float) m[4], (float) m[5], 1));
                budget -= drawTexels(canvas, face.face(), texture, budget);
                canvas.restore();
            }
        }

        private int drawTexels(Canvas canvas, ModelScene.Face face, PixelBuffer texture, int budget) {
            int tw = texture.width();
            int th = texture.height();
            double cellW = 16.0 / tw;
            double cellH = 16.0 / th;
            double[][] uv = face.uv();
            double u0 = Math.min(Math.min(uv[0][0], uv[1][0]), Math.min(uv[2][0], uv[3][0]));
            double u1 = Math.max(Math.max(uv[0][0], uv[1][0]), Math.max(uv[2][0], uv[3][0]));
            double v0 = Math.min(Math.min(uv[0][1], uv[1][1]), Math.min(uv[2][1], uv[3][1]));
            double v1 = Math.max(Math.max(uv[0][1], uv[1][1]), Math.max(uv[2][1], uv[3][1]));
            int firstX = Math.max(0, (int) Math.floor(u0 / cellW));
            int lastX = Math.min(tw - 1, (int) Math.ceil(u1 / cellW) - 1);
            int firstY = Math.max(0, (int) Math.floor(v0 / cellH));
            int lastY = Math.min(th - 1, (int) Math.ceil(v1 / cellH) - 1);
            // very detailed textures on very many faces are drawn with every n-th texel, so a frame stays cheap
            int step = Math.max(1, (int) Math.ceil(Math.sqrt(((double) (lastX - firstX + 1) * (lastY - firstY + 1)) / Math.max(256, budget / 8))));
            float shade = shade(face.direction());
            int drawn = 0;
            for (int y = firstY; y <= lastY; y += step) {
                for (int x = firstX; x <= lastX; x += step) {
                    int argb = texture.get(x, y);
                    if ((argb >>> 24) == 0) {
                        continue;
                    }
                    paint.setColor(shaded(argb, shade));
                    canvas.drawRect((float) Math.max(u0, x * cellW), (float) Math.max(v0, y * cellH),
                            (float) Math.min(u1, (x + step) * cellW + 0.02), (float) Math.min(v1, (y + step) * cellH + 0.02), paint);
                    drawn++;
                }
            }
            return drawn;
        }

        private static float shade(String direction) {
            return switch (direction) {
                case "up" -> 1.0f;
                case "down" -> 0.5f;
                case "north", "south" -> 0.8f;
                default -> 0.6f;
            };
        }

        private static int shaded(int argb, float f) {
            int r = Math.min(255, Math.round(((argb >> 16) & 0xFF) * f));
            int g = Math.min(255, Math.round(((argb >> 8) & 0xFF) * f));
            int b = Math.min(255, Math.round((argb & 0xFF) * f));
            return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
        }

        @Override
        public boolean onTouchEvent(@NonNull MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN -> {
                    lastX = event.getX();
                    lastY = event.getY();
                }
                case MotionEvent.ACTION_MOVE -> {
                    yaw += (event.getX() - lastX) * 0.6;
                    pitch = Math.max(-89, Math.min(89, pitch + (event.getY() - lastY) * 0.6));
                    lastX = event.getX();
                    lastY = event.getY();
                    invalidate();
                }
                default -> {
                }
            }
            return true;
        }

        @Override
        public boolean onGenericMotionEvent(@NonNull MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_SCROLL) {
                float delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (delta != 0) {
                    zoom = Math.max(0.2, Math.min(8.0, zoom * (delta > 0 ? 1.15 : 1 / 1.15)));
                    invalidate();
                    return true;
                }
            }
            return super.onGenericMotionEvent(event);
        }
    }
}
