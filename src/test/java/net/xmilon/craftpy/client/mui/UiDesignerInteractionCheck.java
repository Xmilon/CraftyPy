package net.xmilon.craftpy.client.mui;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import icyllis.modernui.ModernUI;
import icyllis.modernui.R;
import icyllis.modernui.core.Core;
import icyllis.modernui.fragment.Fragment;
import icyllis.modernui.util.DataSet;
import icyllis.modernui.view.LayoutInflater;
import icyllis.modernui.view.MotionEvent;
import icyllis.modernui.view.MeasureSpec;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.TextView;
import icyllis.modernui.widget.FrameLayout;
import net.xmilon.craftpy.client.ClientProjectStore;
import net.xmilon.craftpy.client.IdeHost;
import net.xmilon.craftpy.client.TextOps;

public final class UiDesignerInteractionCheck extends Fragment {
    static final AtomicReference<Throwable> failure = new AtomicReference<>();
    static volatile boolean completed;
    static Path fixtureWindow;
    static final String WINDOW = """
            {"id":"check","size":[400,260],"preview_data":{"caption":"Buy"},"root":{"type":"canvas","height":200,"children":[
              {"type":"button","text":"Buy","bind":{"text":"caption"},"on_click":"buy","x":20,"y":20,"width":96,"height":32}]}}
            """;
    UiDesigner designer;
    View root;
    FrameLayout host;
    EditorFragment editor;
    WindowFragment runtimeWindow;
    icyllis.modernui.widget.SeekBar runtimeSlider;
    String beforeDesign;
    String afterDesign;
    int changes;
    int editorWaits;

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory(Path.of("build"), "ui-designer-").toAbsolutePath();
        System.setProperty("craftpy.home", home.toString());
        Path project = home.resolve("pyforge/projects/check");
        Files.createDirectories(project.resolve("code"));
        Files.createDirectories(project.resolve("ui"));
        Files.writeString(project.resolve("project.json"), "{\"id\":\"check\",\"name\":\"Designer check\",\"version\":\"1.0\"}");
        Files.writeString(project.resolve("code/main.py"), "# Designer integration check\n");
        fixtureWindow = project.resolve("ui/main.json");
        Files.writeString(fixtureWindow, WINDOW);
        net.xmilon.craftpy.project.PyForgePaths.ensureLayout();
        net.xmilon.craftpy.project.ProjectManager.init();
        var constructor = Class.forName("net.xmilon.craftpy.ide.LocalHost").getDeclaredConstructor();
        constructor.setAccessible(true);
        IdeHost.install((IdeHost) constructor.newInstance());
        try (ModernUI app = new ModernUI()) {
            app.setTheme(R.style.Theme_Material3_Dark);
            java.util.concurrent.CompletableFuture.delayedExecutor(20, java.util.concurrent.TimeUnit.SECONDS).execute(() -> {
                if (!completed) {
                    failure.compareAndSet(null, new AssertionError("Designer interaction check timed out"));
                    stop();
                }
            });
            app.run(new UiDesignerInteractionCheck(), handle -> org.lwjgl.glfw.GLFW.glfwHideWindow(handle));
        } finally {
            net.xmilon.craftpy.python.PyRuntime.shutdown();
        }
        if (failure.get() != null) throw new AssertionError("Designer interaction check", failure.get());
        require(completed, "Designer closed before the checks completed");
        System.out.println("WINDOW DESIGNER INTERACTIONS PASSED");
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, DataSet saved) {
        designer = UiDesigner.create(requireContext(), "check", WINDOW, json -> changes++, List.of(), path -> { },
                new UiDesigner.EditorActions(() -> { }, () -> { }, () -> { }));
        root = designer.view();
        root.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
        host = new FrameLayout(requireContext());
        host.setId(0x7f0c0f11);
        host.addView(root);
        later(this::checkMove);
        return host;
    }

    void later(Runnable work) {
        root.postDelayed(() -> {
            try { work.run(); }
            catch (Throwable error) { failure.set(error); error.printStackTrace(); stop(); }
        }, 250);
    }

    JsonObject json() { return JsonParser.parseString(designer.json()).getAsJsonObject(); }
    JsonObject button() { return json().getAsJsonObject("root").getAsJsonArray("children").get(0).getAsJsonObject(); }

    void checkMove() {
        require(root.getWidth() > 0 && root.getHeight() > 0, "Workspace was not laid out");
        Object overlay = field(designer, "preview");
        JsonObject window = (JsonObject) field(designer, "window");
        JsonObject node = window.getAsJsonObject("root").getAsJsonArray("children").get(0).getAsJsonObject();
        View widget = (View) call(overlay, "viewOf", new Class<?>[] {JsonObject.class}, node);
        float[] r = (float[]) call(overlay, "rect", new Class<?>[] {View.class}, widget);
        float x = (r[0] + r[2]) / 2, y = (r[1] + r[3]) / 2;
        float d = root.getContext().getResources().getDisplayMetrics().density;
        pointer((View) overlay, MotionEvent.ACTION_DOWN, x, y);
        pointer((View) overlay, MotionEvent.ACTION_MOVE, x + 20 * d, y + 12 * d);
        pointer((View) overlay, MotionEvent.ACTION_UP, x + 20 * d, y + 12 * d);
        require(button().get("x").getAsInt() == 40 && button().get("y").getAsInt() == 32,
                "Drag did not move the button: " + button());
        require(changes == 1, "A drag must be one document edit");
        require(button().get("on_click").getAsString().equals("buy"), "Event binding was lost");
        later(this::checkResize);
    }

    void checkResize() {
        Object overlay = field(designer, "preview");
        JsonObject selected = (JsonObject) field(designer, "selected");
        View widget = (View) call(overlay, "viewOf", new Class<?>[] {JsonObject.class}, selected);
        float[] r = (float[]) call(overlay, "rect", new Class<?>[] {View.class}, widget);
        float d = root.getContext().getResources().getDisplayMetrics().density;
        pointer((View) overlay, MotionEvent.ACTION_DOWN, r[2], r[3]);
        pointer((View) overlay, MotionEvent.ACTION_MOVE, r[2] + 24 * d, r[3] + 16 * d);
        pointer((View) overlay, MotionEvent.ACTION_UP, r[2] + 24 * d, r[3] + 16 * d);
        require(button().get("width").getAsInt() == 120 && button().get("height").getAsInt() == 48,
                "Resize did not update dimensions: " + button());
        require(changes == 2, "Resize must be one document edit");
        later(this::checkCancel);
    }

    void checkCancel() {
        String before = designer.json();
        Object overlay = field(designer, "preview");
        JsonObject selected = (JsonObject) field(designer, "selected");
        View widget = (View) call(overlay, "viewOf", new Class<?>[] {JsonObject.class}, selected);
        float[] r = (float[]) call(overlay, "rect", new Class<?>[] {View.class}, widget);
        float x = (r[0] + r[2]) / 2, y = (r[1] + r[3]) / 2;
        pointer((View) overlay, MotionEvent.ACTION_DOWN, x, y);
        pointer((View) overlay, MotionEvent.ACTION_MOVE, x + 32, y + 24);
        pointer((View) overlay, MotionEvent.ACTION_CANCEL, x + 32, y + 24);
        require(before.equals(designer.json()) && changes == 2, "Cancelled drag changed the document");
        later(this::checkZoomAndPalette);
    }

    void checkZoomAndPalette() {
        TextView minus = find(root, "−");
        require(minus != null, "Zoom control missing");
        minus.performClick();
        later(() -> {
            Object overlay = field(designer, "preview");
            View box = (View) call(overlay, "viewOf", new Class<?>[] {JsonObject.class},
                    ((JsonObject) field(designer, "window")).getAsJsonObject("root"));
            int[] target = new int[2];
            box.getLocationInWindow(target);
            call(overlay, "paletteDrop", new Class<?>[] {String.class, float.class, float.class, boolean.class},
                    "label", target[0] + 12f, target[1] + 12f, true);
            require(json().getAsJsonObject("root").getAsJsonArray("children").size() == 2,
                    "Palette drop failed at 75% zoom");
            require(changes == 3, "Palette drop must be one document edit");
            later(this::checkToolboxDrag);
        });
    }

    void checkToolboxDrag() {
        TextView checkbox = find(root, "+  Checkbox");
        require(checkbox != null, "Checkbox missing from toolbox");
        Object overlay = field(designer, "preview");
        View box = (View) call(overlay, "viewOf", new Class<?>[] {JsonObject.class},
                ((JsonObject) field(designer, "window")).getAsJsonObject("root"));
        int[] source = new int[2], target = new int[2];
        checkbox.getLocationInWindow(source);
        box.getLocationInWindow(target);
        float x = target[0] + 160 - source[0], y = target[1] + 90 - source[1];
        pointer(checkbox, MotionEvent.ACTION_DOWN, 8, 8);
        pointer(checkbox, MotionEvent.ACTION_MOVE, x, y);
        pointer(checkbox, MotionEvent.ACTION_UP, x, y);
        require(json().getAsJsonObject("root").getAsJsonArray("children").size() == 3,
                "Dragging from toolbox did not add a checkbox");
        require(changes == 4, "Toolbox drag must be one document edit");
        later(this::checkClickPlacement);
    }

    void checkClickPlacement() {
        TextView field = find(root, "+  Text field");
        require(field != null, "Text field missing from toolbox");
        field.performClick();
        Object overlay = field(designer, "preview");
        View box = (View) call(overlay, "viewOf", new Class<?>[] {JsonObject.class},
                ((JsonObject) field(designer, "window")).getAsJsonObject("root"));
        float[] r = (float[]) call(overlay, "rect", new Class<?>[] {View.class}, box);
        pointer((View) overlay, MotionEvent.ACTION_DOWN, r[0] + 200, r[1] + 160);
        pointer((View) overlay, MotionEvent.ACTION_UP, r[0] + 200, r[1] + 160);
        require(json().getAsJsonObject("root").getAsJsonArray("children").size() == 4,
                "Choosing a toolbox tool and clicking the window did not place it");
        require(changes == 5, "Click placement must be one document edit");
        call(designer, "select", new Class<?>[] {JsonObject.class},
                ((JsonObject) field(designer, "window")).getAsJsonObject("root").getAsJsonArray("children").get(0).getAsJsonObject());
        call(designer, "refreshProps", new Class<?>[0]);
        var bindingField = (TextView) described(root, "bind.text");
        require(bindingField != null, "Binding field missing from inspector");
        bindingField.setText("live_caption");
        var sampleField = (TextView) described(root, "Preview sample data JSON object");
        sampleField.setText("{\"live_caption\":\"Bound Buy\"}");
        find(root, "Apply sample data").performClick();
        require(button().getAsJsonObject("bind").get("text").getAsString().equals("live_caption"), "Inspector lost binding");
        require(button().get("text").getAsString().equals("Buy"), "Sample data replaced static fallback");
        require(find(root, "Bound Buy") != null, "Sample data did not reach the preview");
        System.out.println("CHECKED real layout, drag, resize, cancellation, toolbox drag, click placement and zoom");
        root = host;
        editor = new EditorFragment();
        getChildFragmentManager().beginTransaction().replace(host.getId(), editor, "editor").commit();
        later(() -> {
            ClientProjectStore.getInstance().select("check", "ui/main.json");
            later(this::checkEditorTabs);
        });
    }

    String editorText() { return ((TextView) field(editor, "code")).getText().toString(); }

    void editSource(String text) {
        call(editor, "applyEdit", new Class<?>[] {TextOps.Edit.class},
                new TextOps.Edit(0, editorText().length(), text, 0, 0));
    }

    void checkEditorTabs() {
        if (field(editor, "windowDesigner") == null && editorWaits++ < 20) { later(this::checkEditorTabs); return; }
        require(field(editor, "windowDesigner") != null, "Window did not open in the main designer: "
                + ClientProjectStore.getInstance().activeFile() + " / " + field(editor, "shownPath"));
        require(((View) field(editor, "sourceEditors")).getVisibility() == View.GONE, "Source editor overlaps Design");
        UiMakerPanel navigator = (UiMakerPanel) field(editor, "uiMaker");
        call(editor, "setSidePanel", new Class<?>[] {View.class, Splitter.class, boolean.class},
                navigator.view(), field(editor, "uiMakerSplitter"), true);
        call(editor, "refreshWindowDesign", new Class<?>[0]);
        require(navigator.view().getVisibility() == View.VISIBLE, "A designer refresh closed the window navigator");
        find(root, "Source").performClick();
        require(((View) field(editor, "sourceEditors")).getVisibility() == View.VISIBLE, "Source tab did not show code");
        JsonObject source = JsonParser.parseString(editorText()).getAsJsonObject();
        source.addProperty("title", "From Source");
        beforeDesign = source.toString();
        editSource(beforeDesign);
        find(root, "Design").performClick();
        UiDesigner live = (UiDesigner) field(editor, "windowDesigner");
        require(live.json().contains("From Source"), "Design did not read the Source edit");
        call(live, "setWindow", new Class<?>[] {String.class, String.class}, "title", "From Design");
        afterDesign = editorText();
        require(afterDesign.contains("From Design") && afterDesign.contains("buy"), "Design did not update the document");
        find(root, "Undo").performClick();
        require(editorText().equals(beforeDesign), "Undo did not restore the source document");
        later(() -> {
            require(((UiDesigner) field(editor, "windowDesigner")).json().contains("From Source"),
                    "Undo did not update Design");
            find(root, "Redo").performClick();
            require(editorText().equals(afterDesign), "Redo did not restore the design edit");
            find(root, "Save").performClick();
            later(this::checkSavedAndInvalidSource);
        });
    }

    void checkSavedAndInvalidSource() {
        try { require(Files.readString(fixtureWindow).equals(afterDesign), "Save did not write the shared document"); }
        catch (java.io.IOException error) { throw new AssertionError(error); }
        find(root, "Source").performClick();
        editSource("{ invalid source");
        find(root, "Design").performClick();
        require(field(editor, "windowDesigner") == null && editorText().equals("{ invalid source"),
                "Design replaced invalid JSON");
        ClientProjectStore.getInstance().openFile("code/main.py");
        later(() -> {
            require(((View) field(editor, "windowModeBar")).getVisibility() == View.GONE,
                    "Design tabs leaked into an ordinary Python file");
            require(((View) field(editor, "sourceEditors")).getVisibility() == View.VISIBLE,
                    "Ordinary Python editing was not restored");
            System.out.println("CHECKED main editor Design/Source round trip, undo/redo, save, invalid JSON and ordinary files");
            runtimeWindow = WindowFragment.preview(runtimeTree("Before refresh", "server initial"));
            getChildFragmentManager().beginTransaction().replace(host.getId(), runtimeWindow, "runtime").commit();
            later(this::checkRuntimeFocus);
        });
    }

    JsonObject runtimeTree(String caption, String value) {
        var tree = JsonParser.parseString("{\"id\":\"runtime\",\"size\":[420,280],\"root\":{\"type\":\"vbox\",\"children\":["
                + "{\"type\":\"text_input\",\"id\":\"draft\",\"value\":\"\"},"
                + "{\"type\":\"slider\",\"id\":\"level\",\"value\":20},"
                + "{\"type\":\"label\",\"text\":\"\"},"
                + "{\"type\":\"vbox\",\"enabled\":false,\"children\":[{\"type\":\"button\",\"text\":\"Disabled child\"}]}]}}").getAsJsonObject();
        var children = tree.getAsJsonObject("root").getAsJsonArray("children");
        children.get(0).getAsJsonObject().addProperty("value", value);
        children.get(2).getAsJsonObject().addProperty("text", caption);
        return tree;
    }

    void checkRuntimeFocus() {
        var input = (icyllis.modernui.widget.EditText) ((java.util.Map<?, ?>) field(runtimeWindow, "inputs")).get("draft");
        require(input.requestFocus(), "Input cannot take focus");
        input.setText("unfinished draft"); input.setSelection(2, 6);
        require(!find(root, "Disabled child").isEnabled(), "Disabled parent left an actionable child");
        runtimeWindow.update(runtimeTree("After refresh", "changed by server"));
        later(() -> {
            var nextInput = (icyllis.modernui.widget.EditText) ((java.util.Map<?, ?>) field(runtimeWindow, "inputs")).get("draft");
            require(nextInput.hasFocus(), "Refresh lost input focus");
            require(nextInput.getText().toString().equals("unfinished draft"), "Refresh replaced unfinished text");
            require(nextInput.getSelectionStart() == 2 && nextInput.getSelectionEnd() == 6, "Refresh lost selection");
            require(find(root, "After refresh") != null, "Display did not update around the focused input");
            runtimeSlider = first(root, icyllis.modernui.widget.SeekBar.class);
            require(runtimeSlider != null && runtimeSlider.getWidth() > 0, "Slider not laid out");
            pointer(runtimeSlider, MotionEvent.ACTION_DOWN, runtimeSlider.getWidth() / 2f, runtimeSlider.getHeight() / 2f);
            pointer(runtimeSlider, MotionEvent.ACTION_MOVE, runtimeSlider.getWidth() * .75f, runtimeSlider.getHeight() / 2f);
            require((boolean) field(runtimeWindow, "tracking"), "Slider did not start native dragging");
            runtimeWindow.update(runtimeTree("After drag", "changed by server"));
            later(() -> {
                require(field(runtimeWindow, "pending") != null && find(root, "After refresh") != null, "Refresh interrupted slider dragging");
                pointer(runtimeSlider, MotionEvent.ACTION_UP, runtimeSlider.getWidth() * .75f, runtimeSlider.getHeight() / 2f);
                later(() -> {
                    require(!(boolean) field(runtimeWindow, "tracking") && find(root, "After drag") != null, "Deferred refresh did not apply after release");
                    checkBundledWindows();
                    System.out.println("CHECKED binding inspector/sample fallback, focus, unfinished text, selection, disabled children and slider refresh deferral");
                    checkBuildingBlocks();
                });
            });
        });
    }

    void checkBundledWindows() {
        int count = 0;
        for (String folder : List.of("src/main/resources/craftpy_default/welcome", "examples/hud_pack", "examples/jukebox", "examples/sky_islands", "examples/task_journal", "examples/waypoints")) {
            try (var files = Files.list(Path.of(folder, "ui"))) {
                for (Path file : files.filter(path -> path.toString().endsWith(".json")).toList()) {
                    var tree = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                    var problems = new java.util.ArrayList<String>();
                    View preview = WindowFragment.previewView(requireContext(), tree.getAsJsonObject("root"), null, tree.getAsJsonObject("preview_data"), problems);
                    require(problems.isEmpty(), file + ": " + problems);
                    preview.measure(MeasureSpec.makeMeasureSpec(660, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(620, MeasureSpec.AT_MOST));
                    require(preview.getMeasuredWidth() > 0 && preview.getMeasuredHeight() > 0, file + " did not measure");
                    count++;
                }
            } catch (java.io.IOException error) { throw new AssertionError(error); }
        }
        for (var block : net.xmilon.craftpy.project.BuildingBlocks.all()) {
            for (var entry : block.files(block.module(), "preview").entrySet()) {
                if (!entry.getKey().startsWith("ui/")) continue;
                String json = entry.getValue();
                var definition = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                var problems = new java.util.ArrayList<String>();
                View preview = WindowFragment.previewView(requireContext(), definition.getAsJsonObject("root"), null, definition.getAsJsonObject("preview_data"), problems);
                require(problems.isEmpty(), entry.getKey() + ": " + problems);
                preview.measure(MeasureSpec.makeMeasureSpec(660, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(620, MeasureSpec.AT_MOST));
                require(preview.getMeasuredWidth() > 0 && preview.getMeasuredHeight() > 0, entry.getKey() + " did not measure");
                count++;
            }
        }
        require(count == 16, "Expected 16 shipped and template layouts, got " + count);
        System.out.println("CHECKED all 16 shipped/template previews and sample bindings on native Modern UI views");
    }

    void checkBuildingBlocks() {
        var panel = new BlocksPanel(requireContext(), new BlocksPanel.Host() {
            public void docs(View anchor, net.xmilon.craftpy.project.BuildingBlocks.Block block) { }
            public void insert(net.xmilon.craftpy.project.BuildingBlocks.Block block) { }
            public void newFile(View anchor, net.xmilon.craftpy.project.BuildingBlocks.Block block) { }
            public void hide() { }
        });
        var overlays = new Overlays.Host(requireContext()); overlays.addView(panel.view());
        host.removeAllViews(); host.addView(overlays); root = panel.view();
        later(() -> {
            var search = (icyllis.modernui.widget.EditText) field(panel, "search");
            search.setText("designer counter");
            var shown = (List<?>) field(panel, "shown");
            require(shown.size() == 1, "Building Blocks keyword search did not narrow the results");
            var block = (net.xmilon.craftpy.project.BuildingBlocks.Block) shown.getFirst();
            require(block.file().equals("ui/bound_counter.py"), "Wrong search result");
            require(((TextView) field(panel, "count")).getText().toString().startsWith("1 results"), "Wrong result count");
            call(panel, "preview", new Class<?>[] {View.class, net.xmilon.craftpy.project.BuildingBlocks.Block.class}, search, block);
            require(overlays.hasDialog(), "Code/companion preview did not open"); overlays.back();
            search.setText("create");
            require(((List<?>) field(panel, "shown")).stream().noneMatch(value -> !((net.xmilon.craftpy.project.BuildingBlocks.Block) value).mods().isEmpty()), "Cached discovery enabled optional templates");
            search.setText("");
            require(!((List<?>) field(panel, "shown")).isEmpty(), "Core templates disappeared");
            ((TextView) field(panel, "categoryPicker")).performClick();
            String category = net.xmilon.craftpy.project.BuildingBlocks.all().stream().filter(b -> b.file().equals("machines/shaft.py")).findFirst().orElseThrow().category();
            ((View) find(overlays, category).getParent()).performClick();
            require(((TextView) field(panel, "categoryPicker")).getText().toString().equals(category), "Category control did not update");
            require(((List<?>) field(panel, "shown")).stream().allMatch(value -> ((net.xmilon.craftpy.project.BuildingBlocks.Block) value).category().equals(category)), "Category filter included unrelated templates");
            System.out.println("CHECKED native Building Blocks keyword search, counts, core filtering and code/companion preview");
            completed = true; stop();
        });
    }

    static View described(View view, String description) {
        if (description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            View found = described(group.getChildAt(i), description); if (found != null) return found;
        }
        return null;
    }

    static <T> T first(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            T found = first(group.getChildAt(i), type); if (found != null) return found;
        }
        return null;
    }

    static TextView find(View view, String text) {
        if (view instanceof TextView label && label.getText().toString().equals(text)) return label;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = find(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    static void pointer(View view, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(Core.timeNanos(), action, MotionEvent.BUTTON_PRIMARY, x, y, 0,
                action == MotionEvent.ACTION_UP ? 0 : MotionEvent.BUTTON_PRIMARY, 0);
        try { view.dispatchTouchEvent(event); } finally { event.recycle(); }
    }

    static Object field(Object obj, String name) {
        try { Field field = obj.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(obj); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    static Object call(Object obj, String name, Class<?>[] types, Object... args) {
        try { Method method = obj.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(obj, args); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    static void stop() {
        Core.postOnMainThread(() -> {
            try {
                Method stop = ModernUI.class.getDeclaredMethod("stop");
                stop.setAccessible(true);
                stop.invoke(ModernUI.getInstance());
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        org.lwjgl.glfw.GLFW.glfwPostEmptyEvent();
    }
}

