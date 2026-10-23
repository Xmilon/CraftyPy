package net.xmilon.craftpy.client.mui;

import net.xmilon.craftpy.client.IdeHost;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import icyllis.modernui.R;
import icyllis.modernui.annotation.Nullable;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.drawable.ShapeDrawable;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.TextView;
import icyllis.modernui.text.Typeface;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.xmilon.craftpy.analysis.PyDocstring;
import net.xmilon.craftpy.analysis.PyImports;
import net.xmilon.craftpy.analysis.PyOutline;
import net.xmilon.craftpy.client.JumpHistory;
import net.xmilon.craftpy.client.LootPreview;
import net.xmilon.craftpy.client.RecipePreview;
import net.xmilon.craftpy.client.Syntax;
import net.xmilon.craftpy.client.TextOps;
import net.xmilon.craftpy.registry.SlotRegistry;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static net.xmilon.craftpy.client.mui.CraftPyFragment.*;

/**
 * The editor's project-aware tools (Batch B and C): symbol search, breadcrumbs, jump history, replace
 * preview, imports, docstrings, rename-everywhere, asset usage, "why isn't it showing", content / recipe /
 * loot previews, the handler profiler and the event tracer. {@link EditorFragment} owns the text view and
 * the store; this class talks to it through {@link Host}. All methods run on the UI thread; server replies
 * arrive on the client thread and are posted back.
 */
final class EditorTools {

    /** What the tools need from the editor. */
    interface Host {
        View anchor();

        @Nullable
        String projectId();

        @Nullable
        String activePath();

        String text();

        void designWindow();

        void openWindow(String path);

        int caret();

        int selectionStart();

        int selectionEnd();

        /** Bumped on every text change; lets the tools cache the parsed outline. */
        int textVersion();

        /** Replaces {@code [start, end)} and selects {@code [cursorStart, cursorEnd]}. */
        void replace(int start, int end, String text, int cursorStart, int cursorEnd);

        void openAt(String path, int line);

        void goToLine(int line);

        void toast(String text);

        /** Unsaved open files (path to current text); the active one uses the live editor text. */
        Map<String, String> dirtyTexts();

        /** The project's breakpoints by file (0-based lines). */
        Map<String, java.util.TreeSet<Integer>> breakpoints();

        /** Removes every breakpoint of the project (and redraws the gutter). */
        void clearBreakpoints();

        /** Shows a read-only built-in file (the generated API) and moves to a 0-based line. */
        void openLibrary(String path, String text, int line);

        /** Saves every open file with unsaved changes (before a tool reads them on the server). */
        void saveAll();
    }

    private static final int TRACE_LINES = 400;
    private static final java.time.format.DateTimeFormatter TIME = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final Host host;
    final JumpHistory jumps = new JumpHistory();

    // Event tracer pane
    private TextView traceText;
    private ScrollView traceScroll;
    private TextView traceToggle;
    private TextView traceStatus;
    private final List<String> traceLines = new ArrayList<>();
    private boolean traceVisible;
    private boolean traceEnabled;
    private long traceLast;

    EditorTools(Host host) {
        this.host = host;
    }

    /** Server replies can arrive after the editor page was closed; drop them instead of touching dead views. */
    private void postUi(Runnable action) {
        View anchor = host.anchor();
        if (anchor != null) {
            anchor.post(action);
        }
    }

    // ------------------------------------------------------------------
    // outline cache shared by breadcrumbs, sticky scroll and focus mode
    // ------------------------------------------------------------------

    private List<PyOutline.Symbol> cachedSymbols = List.of();
    private int cachedVersion = -1;
    private String cachedPath;

    private List<PyOutline.Symbol> symbols() {
        String path = host.activePath();
        if (path == null || !"py".equals(Syntax.language(path))) {
            return List.of();
        }
        if (cachedVersion != host.textVersion() || !path.equals(cachedPath)) {
            cachedSymbols = PyOutline.parse(host.text());
            cachedVersion = host.textVersion();
            cachedPath = path;
        }
        return cachedSymbols;
    }

    /** 1: signatures of the scopes whose header scrolled out above {@code firstVisibleLine} (outermost first, max 3). */
    List<String> stickyHeaders(int firstVisibleLine) {
        List<String> out = new ArrayList<>();
        for (PyOutline.Symbol s : PyOutline.chain(symbols(), firstVisibleLine)) {
            if (s.line() < firstVisibleLine) {
                out.add(s.signature());
            }
        }
        return out.size() > 3 ? out.subList(out.size() - 3, out.size()) : out;
    }

    /** 26: character range of the innermost function/class around the caret, or null (no scope: show everything). */
    @Nullable
    int[] focusRange() {
        String text = host.text();
        int caret = Math.max(0, Math.min(host.caret(), text.length()));
        PyOutline.Symbol scope = PyOutline.innermost(symbols(), TextOps.lineOf(text, caret));
        if (scope == null) {
            return null;
        }
        int start = TextOps.offsetOfLine(text, scope.line());
        int endOffset = TextOps.lineEnd(text, TextOps.offsetOfLine(text, scope.endLine()));
        return new int[] { start, endOffset };
    }

    // ------------------------------------------------------------------
    // 30 extract variable / function
    // ------------------------------------------------------------------

    void extractVariable() {
        if (!isPython()) {
            return;
        }
        int from = Math.min(host.selectionStart(), host.selectionEnd());
        int to = Math.max(host.selectionStart(), host.selectionEnd());
        if (to <= from) {
            host.toast("Select the expression to extract first");
            return;
        }
        prompt(host.anchor(), "Name of the new variable", "value", "Extract", name -> {
            var result = net.xmilon.craftpy.analysis.PyRefactor.extractVariable(host.text(), from, to, name);
            applyRefactor(result);
        });
    }

    void extractFunction() {
        if (!isPython()) {
            return;
        }
        String text = host.text();
        int from = Math.min(host.selectionStart(), host.selectionEnd());
        int to = Math.max(host.selectionStart(), host.selectionEnd());
        if (to <= from) {
            host.toast("Select the lines to move into a function first");
            return;
        }
        int firstLine = TextOps.lineOf(text, from);
        int lastLine = TextOps.lineOf(text, text.charAt(to - 1) == '\n' ? to - 1 : to);
        prompt(host.anchor(), "Name of the new function", "extracted", "Extract", name -> {
            var result = net.xmilon.craftpy.analysis.PyRefactor.extractFunction(host.text(), firstLine, lastLine, name);
            applyRefactor(result);
        });
    }

    private void applyRefactor(net.xmilon.craftpy.analysis.PyRefactor.Result result) {
        if (result.replacement() == null) {
            host.toast(result.message());
            return;
        }
        PyImports.Replacement r = result.replacement();
        host.replace(r.start(), r.end(), r.text(), result.cursorStart(), result.cursorEnd());
        host.toast(result.message());
    }

    // ------------------------------------------------------------------
    // 22 breadcrumbs
    // ------------------------------------------------------------------

    /** {@code code/main.py > Counter > bump} for the caret position. */
    String breadcrumb() {
        String path = host.activePath();
        if (path == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(path.replace("/", " › "));
        if ("py".equals(Syntax.language(path))) {
            String text = host.text();
            int line = TextOps.lineOf(text, Math.max(0, Math.min(host.caret(), text.length())));
            for (PyOutline.Symbol s : PyOutline.chain(symbols(), line)) {
                out.append(" › ").append(s.name());
            }
        }
        return out.toString();
    }

    /** Outline of the current file; click a symbol to jump. */
    void showOutline(View anchor) {
        if (host.activePath() == null || !"py".equals(Syntax.language(host.activePath()))) {
            host.toast("The outline works for Python files");
            return;
        }
        var menu = Overlays.menu(anchor);
        int count = 0;
        for (PyOutline.Symbol s : PyOutline.parse(host.text())) {
            if (s.kind() == PyOutline.Kind.CONSTANT || count >= 60) {
                continue;
            }
            int line = s.line();
            menu.getMenu().add("    ".repeat(s.parent().isEmpty() ? 0 : s.parent().split("\\.").length)
                    + tag(s) + " " + s.name()).setOnMenuItemClickListener(i -> {
                        recordJump();
                        host.goToLine(line);
                        return true;
                    });
            count++;
        }
        if (count == 0) {
            host.toast("No functions or classes in this file");
            return;
        }
        menu.show();
    }

    // ------------------------------------------------------------------
    // 24 jump history
    // ------------------------------------------------------------------

    @Nullable
    JumpHistory.Location here() {
        String path = host.activePath();
        if (path == null) {
            return null;
        }
        String text = host.text();
        return new JumpHistory.Location(path, TextOps.lineOf(text, Math.max(0, Math.min(host.caret(), text.length()))));
    }

    /** Call just before a jump to remember where the cursor was. */
    void recordJump() {
        jumps.push(here());
    }

    private String libraryText;
    private String libraryPath;

    /** Ctrl+click / Ctrl+B: this file, then the craftpy API (generated library file), then the whole project. */
    void goToDefinition(int offset) {
        String text = host.text();
        int[] word = TextOps.wordAt(text, offset);
        if (word == null || host.projectId() == null) {
            host.toast("No name here");
            return;
        }
        String symbol = text.substring(word[0], word[1]);
        int[] slice = CodeAssist.slice(text, offset);
        int sliceLine = CodeAssist.lineOf(text, slice[0]);
        JsonObject args = new JsonObject();
        args.addProperty("code", text.substring(slice[0], slice[1]));
        args.addProperty("line", CodeAssist.lineOf(text, offset) - sliceLine);
        args.addProperty("column", offset - CodeAssist.lineStart(text, offset));
        store(s -> s.tool("definition", args, reply -> postUi(() -> {
            String kind = reply.has("kind") ? reply.get("kind").getAsString() : "none";
            int line = reply.has("line") ? reply.get("line").getAsInt() : 0;
            switch (kind) {
                case "local" -> {
                    recordJump();
                    host.goToLine(line + sliceLine);
                }
                case "api" -> openLibrary(reply.has("path") ? reply.get("path").getAsString() : "library/craftpy_api.py", line);
                default -> findInProject(symbol);
            }
        })));
    }

    private void findInProject(String symbol) {
        store(s -> {
            s.setDefinitionCallback((file, line) -> postUi(() -> {
                if (file == null || file.isEmpty() || line < 0) {
                    host.toast("No definition found for '" + symbol + "'");
                    return;
                }
                recordJump();
                host.openAt(file, line);
            }));
            s.findDefinition(symbol);
        });
    }

    private void openLibrary(String path, int line) {
        if (libraryText != null) {
            recordJump();
            host.openLibrary(libraryPath, libraryText, line);
            return;
        }
        store(s -> s.tool("stub", new JsonObject(), reply -> postUi(() -> {
            if (!reply.has("text")) {
                host.toast("The API library is not available (is Python running?)");
                return;
            }
            libraryPath = reply.has("path") ? reply.get("path").getAsString() : path;
            libraryText = reply.get("text").getAsString();
            recordJump();
            host.openLibrary(libraryPath, libraryText, line);
        })));
    }

    void back() {
        go(jumps.back(here()), "No earlier location");
    }

    void forward() {
        go(jumps.forward(), "No later location");
    }

    private void go(@Nullable JumpHistory.Location target, String none) {
        if (target == null) {
            host.toast(none);
            return;
        }
        host.openAt(target.path(), target.line());
    }

    void showRecentLocations() {
        List<JumpHistory.Location> recent = jumps.recent();
        var dialog = ListDialog.open(host.anchor(), "Recent locations", false, 480, 260);
        if (recent.isEmpty()) {
            dialog.setMessage("Nothing yet - jump to a bookmark, problem or symbol first.");
            return;
        }
        dialog.setMessage("Alt+Left / Alt+Right step through these.");
        List<ListDialog.Row> rows = new ArrayList<>();
        for (JumpHistory.Location location : recent) {
            rows.add(ListDialog.Row.link(location.path() + ":" + (location.line() + 1), "", () ->
                    host.openAt(location.path(), location.line())));
        }
        dialog.setRows(rows);
    }

    // ------------------------------------------------------------------
    // 21 symbol search
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // In-game windows: ui/<id>.json files and the visual designer
    // ------------------------------------------------------------------

    /** Opens the visual designer on the active {@code ui/*.json} tab. */
    void uiDesigner() {
        String path = host.activePath();
        if (path == null || !path.startsWith("ui/") || !path.endsWith(".json")) {
            host.toast("Open a file inside ui/ first (Tools > New UI window creates one)");
            return;
        }
        host.designWindow();
    }

    /**
     * Where the project works, chosen when it was made: switch it here (and back: the dialog names the old side).
     * Building uses this side; the building blocks and the code checks follow it.
     */
    void projectSide() {
        String project = host.projectId();
        if (project == null) {
            host.toast("Open a project first");
            return;
        }
        var store = net.xmilon.craftpy.client.ClientProjectStore.getInstance();
        var summary = store.find(project);
        var current = net.xmilon.craftpy.project.ModuleSide.of(summary == null ? null : summary.side);
        var dialog = ListDialog.open(host.anchor(), "Project side of " + project, false, 560, 260);
        dialog.setMessage("Now: " + BuildDialog.sideName(current) + ". Client only = Python on each player's computer"
                + " (HUD, keys, windows), on any server, no blocks or items. Server only = behaviour, players need nothing."
                + " Client and server = the usual project. Nothing in your files changes; the checks and building follow.");
        List<ListDialog.Row> rows = new ArrayList<>();
        for (var side : net.xmilon.craftpy.project.ModuleSide.values()) {
            String text = (side == current ? "\u2713 " : "   ") + BuildDialog.sideName(side);
            rows.add(ListDialog.Row.link(text, side == current ? "current" : "switch", () -> {
                dialog.dismiss();
                if (side == current) {
                    return;
                }
                JsonObject args = new JsonObject();
                args.addProperty("side", side.id());
                store(s -> s.tool("set_side", args, reply -> postUi(() -> {
                    if (reply.has("error")) {
                        host.toast(reply.get("error").getAsString());
                        return;
                    }
                    var before = net.xmilon.craftpy.project.ModuleSide.of(reply.get("before").getAsString());
                    host.toast("Now " + BuildDialog.sideName(side) + " - Tools > Project side switches back to "
                            + BuildDialog.sideName(before));
                    store(net.xmilon.craftpy.client.ClientProjectStore::refresh);
                })));
            }));
        }
        dialog.setRows(rows);
    }

    /** Creates {@code ui/<name>.json}; open it and start the designer from the Tools menu. */
    void newWindow() {
        prompt(host.anchor(), "New window id (letters, digits, _)", "main", "Create", name -> {
            String clean = name.trim().toLowerCase(java.util.Locale.ROOT);
            if (!clean.matches("[a-z0-9_]+")) {
                host.toast("Use only a-z, 0-9 and _");
                return;
            }
            String path = "ui/" + clean + ".json";
            store(s -> s.createEntry(path, "file"));
            // open it once the server made it: the UI maker then designs it (an empty file starts a new window)
            host.anchor().postDelayed(() -> host.openWindow(path), 700);
            host.toast("Created " + path + " - open Design to add components");
        });
    }

    void symbolSearch() {
        if (host.projectId() == null) {
            host.toast("Open a project first");
            return;
        }
        var dialog = ListDialog.open(host.anchor(), "Go to symbol", true, 620, 320);
        Map<String, String> dirty = host.dirtyTexts();
        dialog.setMessage("Loading project symbols ... (unsaved files are included)");
        dialog.setRows(symbolRows(new JsonArray(), dirty));
        JsonObject args = new JsonObject();
        store(s -> s.tool("symbols", args, reply -> postUi(() -> {
            if (!dialog.isShowing()) {
                return;
            }
            if (reply.has("error")) {
                dialog.setMessage(reply.get("error").getAsString());
                return;
            }
            JsonArray rows = reply.getAsJsonArray("rows");
            dialog.setRows(symbolRows(rows, dirty));
            dialog.setMessage(rows.size() + " symbols" + (reply.has("rowsCut") ? " (list shortened)" : "")
                    + " - type to filter, Enter opens the first");
        })));
    }

    private List<ListDialog.Row> symbolRows(JsonArray server, Map<String, String> dirty) {
        List<ListDialog.Row> rows = new ArrayList<>();
        for (JsonElement element : server) {
            JsonObject row = element.getAsJsonObject();
            String file = row.get("f").getAsString();
            if (dirty.containsKey(file)) {
                continue; // the unsaved text below wins
            }
            rows.add(symbolRow(row.get("n").getAsString(), PyOutline.Kind.valueOf(row.get("k").getAsString()),
                    file, row.get("l").getAsInt(), row.get("d").getAsString()));
        }
        for (var entry : dirty.entrySet()) {
            if (!entry.getKey().endsWith(".py")) {
                continue;
            }
            for (PyOutline.Symbol s : PyOutline.parse(entry.getValue())) {
                rows.add(symbolRow(s.qualifiedName(), s.kind(), entry.getKey(), s.line(),
                        s.kind() == PyOutline.Kind.CONTENT ? s.detail() + "(\"" + s.contentId() + "\")" : s.detail()));
            }
        }
        rows.sort(Comparator.comparing(ListDialog.Row::text, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    private ListDialog.Row symbolRow(String name, PyOutline.Kind kind, String file, int line, String detail) {
        String shown = tag(kind) + "  " + name;
        return ListDialog.Row.link(shown, file + ":" + (line + 1) + (detail.isEmpty() ? "" : "   " + detail), () -> {
            recordJump();
            host.openAt(file, line);
        });
    }

    private static String tag(PyOutline.Symbol s) {
        return tag(s.kind());
    }

    private static String tag(PyOutline.Kind kind) {
        return switch (kind) {
            case CLASS -> "class";
            case FUNCTION, METHOD -> "def";
            case HANDLER -> "@on";
            case COMMAND -> "/cmd";
            case CONTENT -> "content";
            case CONSTANT -> "const";
        };
    }

    // ------------------------------------------------------------------
    // 29 replace preview
    // ------------------------------------------------------------------

    void replacePreview(List<int[]> matches, String replacement) {
        if (matches.isEmpty()) {
            host.toast("Nothing to replace");
            return;
        }
        final String text = host.text();
        List<TextOps.ReplaceRow> preview = TextOps.previewReplace(text, matches, replacement);
        boolean[] accept = new boolean[preview.size()];
        java.util.Arrays.fill(accept, true);
        var dialog = ListDialog.open(host.anchor(), "Replace preview", false, 680, 320);
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            List<ListDialog.Row> rows = new ArrayList<>();
            int selected = 0;
            for (TextOps.ReplaceRow row : preview) {
                boolean on = accept[row.index()];
                selected += on ? 1 : 0;
                int index = row.index();
                rows.add(ListDialog.Row.toggle((on ? "[x]  " : "[  ]  ") + "line " + (row.line() + 1),
                        row.before() + "\n  →  " + row.after(), () -> {
                            accept[index] = !accept[index];
                            refresh[0].run();
                        }));
            }
            dialog.setMessage(selected + " of " + preview.size() + " will be replaced. Click a match to skip or accept it.");
            dialog.setRows(rows);
        };
        refresh[0].run();
        dialog.addAction("Apply", () -> {
            if (!host.text().equals(text)) {
                host.toast("The text changed - open the preview again");
                return;
            }
            String out = TextOps.replaceMatches(text, matches, accept, replacement);
            dialog.dismiss();
            host.replace(0, text.length(), out, Math.min(host.caret(), out.length()), Math.min(host.caret(), out.length()));
            host.toast("Replaced " + count(accept) + " match(es)");
        });
        dialog.addAction("Skip all", () -> {
            java.util.Arrays.fill(accept, false);
            refresh[0].run();
        });
        dialog.addAction("Accept all", () -> {
            java.util.Arrays.fill(accept, true);
            refresh[0].run();
        });
    }

    private static int count(boolean[] flags) {
        int n = 0;
        for (boolean flag : flags) {
            n += flag ? 1 : 0;
        }
        return n;
    }

    // ------------------------------------------------------------------
    // 32 organise imports, 42 import helper, 36 docstring
    // ------------------------------------------------------------------

    void organiseImports() {
        if (!isPython()) {
            return;
        }
        PyImports.Result result = PyImports.organise(host.text());
        if (result.replacement() == null) {
            host.toast(result.message());
            return;
        }
        apply(result.replacement());
        host.toast(result.message() + (result.removed().isEmpty() ? "" : ": " + String.join(", ", result.removed())));
    }

    void addMissingImports() {
        if (!isPython()) {
            return;
        }
        List<String> missing = PyImports.missing(host.text());
        if (missing.isEmpty()) {
            host.toast("No missing imports found (random., math., ... without an import)");
            return;
        }
        PyImports.Result result = PyImports.organise(host.text(), missing, false);
        if (result.replacement() == null) {
            host.toast(result.message());
            return;
        }
        apply(result.replacement());
        host.toast("Added import " + String.join(", ", missing));
    }

    void generateDocstring() {
        if (!isPython()) {
            return;
        }
        String text = host.text();
        int line = TextOps.lineOf(text, Math.max(0, Math.min(host.caret(), text.length())));
        PyDocstring.Result result = PyDocstring.generate(text, line);
        if (result.replacement() == null) {
            host.toast(result.message());
            return;
        }
        PyImports.Replacement r = result.replacement();
        // Select the summary sentence so typing replaces it.
        int quotes = r.text().indexOf("\"\"\"");
        int summaryStart = r.start() + quotes + 3;
        int summaryEnd = r.start() + r.text().indexOf('\n');
        host.replace(r.start(), r.end(), r.text(), summaryStart, summaryEnd);
        host.toast(result.message());
    }

    private boolean isPython() {
        boolean py = host.activePath() != null && "py".equals(Syntax.language(host.activePath()));
        if (!py) {
            host.toast("This works on Python files");
        }
        return py;
    }

    private void apply(PyImports.Replacement r) {
        int caret = host.caret();
        int delta = r.text().length() - (r.end() - r.start());
        int cursor = caret >= r.end() ? caret + delta : Math.min(caret, r.start() + r.text().length());
        host.replace(r.start(), r.end(), r.text(), cursor, cursor);
    }

    // ------------------------------------------------------------------
    // 33 rename a block/item id everywhere
    // ------------------------------------------------------------------

    void renameId() {
        if (host.projectId() == null) {
            host.toast("Open a project first");
            return;
        }
        Map<String, String> dirty = host.dirtyTexts();
        if (!dirty.isEmpty()) {
            host.toast("Save these files first (Ctrl+S): " + String.join(", ", dirty.keySet()));
            return;
        }
        prompt(host.anchor(), "Block / Item id to rename", contentIdAtCaret(), "Next", from ->
                prompt(host.anchor(), "New id for '" + from + "'", from, "Preview", to -> requestRename(from, to, false)));
    }

    /** F6: moves the top-level def/class at the caret to another file; a preview lists the files that change. */
    void moveSymbol() {
        if (host.projectId() == null) {
            host.toast("Open a project first");
            return;
        }
        Map<String, String> dirty = host.dirtyTexts();
        if (!dirty.isEmpty()) {
            host.toast("Save these files first (Ctrl+S): " + String.join(", ", dirty.keySet()));
            return;
        }
        String text = host.text();
        int line = TextOps.lineOf(text, Math.max(0, Math.min(host.caret(), text.length())));
        PyOutline.Symbol top = null;
        for (PyOutline.Symbol s : PyOutline.chain(PyOutline.parse(text), line)) {
            if (s.indent() == 0 && s.isScope()) {
                top = s;
            }
        }
        if (top == null) {
            host.toast("Put the cursor in a top-level def or class");
            return;
        }
        String name = top.name();
        String source = host.activePath();
        prompt(host.anchor(), "Move " + name + " to file", "code/" + name.toLowerCase(java.util.Locale.ROOT) + ".py", "Preview",
                target -> requestMove(source, name, target, false));
    }

    private void requestMove(String source, String name, String target, boolean apply) {
        JsonObject args = new JsonObject();
        args.addProperty("source", source);
        args.addProperty("name", name);
        args.addProperty("target", target);
        args.addProperty("apply", apply);
        var dialog = apply ? null : ListDialog.open(host.anchor(), "Move " + name + " to " + target, false, 620, 260);
        if (dialog != null) {
            dialog.setMessage("Checking the project ...");
        }
        store(s -> s.tool("move_symbol", args, reply -> postUi(() -> {
            if (reply.has("error")) {
                String error = reply.get("error").getAsString();
                if (dialog != null) {
                    dialog.setMessage(error);
                }
                host.toast(error);
                return;
            }
            if (apply) {
                host.toast(reply.has("message") ? reply.get("message").getAsString() : "Moved");
                String active = host.activePath();
                store(st -> st.reloadAfterDiskChange(active));
                return;
            }
            List<ListDialog.Row> rows = new ArrayList<>();
            for (JsonElement file : reply.getAsJsonArray("files")) {
                String path = file.getAsString();
                String open = path.replace("  (new file)", "");
                rows.add(ListDialog.Row.link(path, "changes", () -> host.openAt(open, 0)));
            }
            dialog.setMessage(reply.get("message").getAsString() + " - these files change:");
            dialog.setRows(rows);
            dialog.addAction("Move", () -> {
                dialog.dismiss();
                requestMove(source, name, target, true);
            });
        })));
    }

    private void requestRename(String from, String to, boolean apply) {
        JsonObject args = new JsonObject();
        args.addProperty("from", from);
        args.addProperty("to", to);
        args.addProperty("apply", apply);
        var dialog = apply ? null : ListDialog.open(host.anchor(), "Rename " + from + " → " + to, false, 700, 300);
        if (dialog != null) {
            dialog.setMessage("Scanning the project ...");
        }
        store(s -> s.tool("rename", args, reply -> postUi(() -> {
            if (reply.has("error")) {
                String error = reply.get("error").getAsString();
                if (dialog != null) {
                    dialog.setMessage(error);
                }
                host.toast(error);
                return;
            }
            if (apply) {
                boolean applied = reply.has("applied") && reply.get("applied").getAsBoolean();
                host.toast(reply.has("message") ? reply.get("message").getAsString() : applied ? "Renamed" : "Not renamed");
                String active = host.activePath();
                store(st -> st.reloadAfterDiskChange(active));
                return;
            }
            JsonArray edits = reply.getAsJsonArray("edits");
            JsonArray moves = reply.getAsJsonArray("moves");
            List<ListDialog.Row> rows = new ArrayList<>();
            for (JsonElement e : edits) {
                JsonObject edit = e.getAsJsonObject();
                String file = edit.get("file").getAsString();
                int line = edit.get("line").getAsInt();
                rows.add(ListDialog.Row.link(file + ":" + (line + 1),
                        edit.get("before").getAsString() + "\n  →  " + edit.get("after").getAsString(),
                        () -> host.openAt(file, line)));
            }
            for (JsonElement m : moves) {
                rows.add(ListDialog.Row.info("move  " + m.getAsString()));
            }
            for (JsonElement w : reply.getAsJsonArray("warnings")) {
                rows.add(ListDialog.Row.info(w.getAsString()).colored(R.attr.colorTertiary));
            }
            dialog.setMessage(edits.size() + " line(s) change, " + moves.size() + " file(s) move"
                    + (reply.has("editsCut") ? " (list shortened, all are applied)" : "") + ". Nothing is written yet.");
            dialog.setRows(rows);
            dialog.addAction("Apply rename", () -> {
                dialog.dismiss();
                requestRename(from, to, true);
            });
        })));
    }

    // ------------------------------------------------------------------
    // 48 asset usage
    // ------------------------------------------------------------------

    void assetUsage() {
        if (host.projectId() == null) {
            host.toast("Open a project first");
            return;
        }
        var dialog = ListDialog.open(host.anchor(), "Asset usage", true, 680, 320);
        dialog.setMessage("Scanning ...");
        boolean unsaved = !host.dirtyTexts().isEmpty();
        store(s -> s.tool("usage", new JsonObject(), reply -> postUi(() -> {
            if (reply.has("error")) {
                dialog.setMessage(reply.get("error").getAsString());
                return;
            }
            List<ListDialog.Row> unused = new ArrayList<>();
            List<ListDialog.Row> used = new ArrayList<>();
            List<ListDialog.Row> missing = new ArrayList<>();
            for (JsonElement e : reply.getAsJsonArray("missing")) {
                JsonObject m = e.getAsJsonObject();
                String file = m.get("file").getAsString();
                int line = m.get("line").getAsInt();
                missing.add(ListDialog.Row.link("Missing: " + m.get("value").getAsString(),
                        file + ":" + (line + 1) + "  - no such file in assets/", () -> host.openAt(file, line))
                        .colored(R.attr.colorError));
            }
            for (JsonElement e : reply.getAsJsonArray("assets")) {
                JsonObject a = e.getAsJsonObject();
                String path = a.get("path").getAsString();
                int count = a.get("count").getAsInt();
                List<String> refs = new ArrayList<>();
                a.getAsJsonArray("refs").forEach(r -> refs.add(r.getAsString()));
                if (count == 0) {
                    unused.add(ListDialog.Row.link("Unused: " + path, "nothing in code/ or data/ refers to it",
                            () -> host.openAt(path, 0)).colored(R.attr.colorTertiary));
                } else {
                    used.add(ListDialog.Row.link(path, count + " use(s): " + String.join(", ", refs)
                            + (count > refs.size() ? ", ..." : ""), () -> host.openAt(path, 0)));
                }
            }
            List<ListDialog.Row> rows = new ArrayList<>(missing);
            rows.addAll(unused);
            rows.addAll(used);
            dialog.setMessage(missing.size() + " missing, " + unused.size() + " unused, " + used.size() + " used"
                    + (unused.isEmpty() ? "" : " - unused ones can be deleted in the explorer")
                    + (unsaved ? ". Unsaved edits are not included." : ""));
            dialog.setRows(rows);
        })));
    }

    // ------------------------------------------------------------------
    // 49 "why isn't my block showing"
    // ------------------------------------------------------------------

    void explain() {
        if (host.projectId() == null) {
            host.toast("Open a project first");
            return;
        }
        prompt(host.anchor(), "Block / Item id to check", contentIdAtCaret(), "Check", this::requestExplain);
    }

    private void requestExplain(String id) {
        var dialog = ListDialog.open(host.anchor(), "Why isn't '" + id + "' showing?", false, 680, 300);
        dialog.setMessage("Checking ...");
        JsonObject args = new JsonObject();
        args.addProperty("id", id);
        store(s -> s.tool("explain", args, reply -> postUi(() -> {
            if (reply.has("error")) {
                dialog.setMessage(reply.get("error").getAsString());
                return;
            }
            List<ListDialog.Row> rows = new ArrayList<>();
            boolean problem = false;
            for (JsonElement e : reply.getAsJsonArray("checks")) {
                JsonObject check = e.getAsJsonObject();
                String level = check.get("level").getAsString();
                String text = check.get("text").getAsString();
                problem |= level.equals("error");
                rows.add(switch (level) {
                    case "ok" -> ListDialog.Row.info("✔  " + text).colored(R.attr.colorPrimary);
                    case "error" -> ListDialog.Row.info("✖  " + text).colored(R.attr.colorError);
                    case "warn" -> ListDialog.Row.info("!  " + text).colored(R.attr.colorTertiary);
                    default -> ListDialog.Row.info("i  " + text).colored(R.attr.colorOnSurfaceVariant);
                });
            }
            dialog.setMessage(problem ? "Found a reason - fix the red line(s) first." : "No problem found on the server side.");
            dialog.setRows(rows);
        })));
    }

    // ------------------------------------------------------------------
    // 43 block/item preview, 46 recipes, 47 loot
    // ------------------------------------------------------------------

    /** 52: what was saved, created, renamed or deleted in this project, newest first. */
    void changeLog() {
        if (host.projectId() == null) {
            host.toast("Open a project first");
            return;
        }
        var dialog = ListDialog.open(host.anchor(), "Change log", true, 620, 320);
        dialog.setMessage("Loading ...");
        store(s -> s.tool("changes", new JsonObject(), reply -> postUi(() -> {
            if (reply.has("error")) {
                dialog.setMessage(reply.get("error").getAsString());
                return;
            }
            List<ListDialog.Row> rows = new ArrayList<>();
            for (JsonElement e : reply.getAsJsonArray("rows")) {
                rows.add(ListDialog.Row.info(e.getAsString()));
            }
            dialog.setMessage(rows.isEmpty() ? "Nothing recorded yet." : rows.size() + " change(s), newest first.");
            dialog.setRows(rows);
        })));
    }

    // ------------------------------------------------------------------
    // 40 handler profiler
    // ------------------------------------------------------------------

    /**
     * Runs the {@code test*} functions of {@code code/test_*.py} on the worker (the open file only when it is a test file
     * and {@code thisFile}) and lists them: failures first, a click opens the line.
     */
    void runTests(boolean thisFile) {
        String file = thisFile ? host.activePath() : null;
        boolean single = file != null && file.startsWith("code/") && file.substring(file.lastIndexOf('/') + 1).startsWith("test_")
                && file.endsWith(".py");
        if (thisFile && !single) {
            host.toast("This is not a test file: name it code/test_<something>.py");
            return;
        }
        host.saveAll();
        var dialog = ListDialog.open(host.anchor(), single ? "Tests of " + file : "Tests", true, 720, 360);
        Runnable[] load = new Runnable[1];
        load[0] = () -> {
            dialog.setMessage("Running tests on the Python worker ...");
            JsonObject args = new JsonObject();
            args.addProperty("file", single ? file : "");
            store(s -> s.tool("run_tests", args, reply -> postUi(() -> showTests(dialog, reply))));
        };
        dialog.addAction("Run again", () -> load[0].run());
        load[0].run();
    }

    private void showTests(ListDialog dialog, JsonObject reply) {
        if (reply.has("error")) {
            dialog.setMessage(reply.get("error").getAsString());
            dialog.setRows(List.of());
            return;
        }
        List<ListDialog.Row> failed = new java.util.ArrayList<>();
        List<ListDialog.Row> passed = new java.util.ArrayList<>();
        JsonArray results = reply.has("results") ? reply.getAsJsonArray("results") : new JsonArray();
        for (JsonElement element : results) {
            JsonObject row = element.getAsJsonObject();
            String path = row.get("file").getAsString();
            int line = row.get("line").getAsInt();
            String where = path + ":" + (line + 1) + "   " + row.get("ms").getAsDouble() + " ms";
            if (row.get("ok").getAsBoolean()) {
                passed.add(ListDialog.Row.link("\u2714 " + row.get("name").getAsString(), where, () -> host.openAt(path, line))
                        .colored(R.attr.colorTertiary));
            } else {
                String message = row.has("message") ? row.get("message").getAsString() : "failed";
                failed.add(ListDialog.Row.link("\u2718 " + row.get("name").getAsString() + ": " + message, where,
                        () -> host.openAt(path, line)).colored(R.attr.colorError));
            }
        }
        List<ListDialog.Row> rows = new java.util.ArrayList<>(failed);
        rows.addAll(passed);
        int files = reply.has("files") ? reply.getAsJsonArray("files").size() : 0;
        dialog.setMessage(results.isEmpty()
                ? (files == 0 ? "No tests: add code/test_<something>.py with functions named test_<something> that use assert."
                : "No test functions in " + files + " file(s): their names must start with test.")
                : failed.size() + " failed, " + passed.size() + " passed (" + files + " file(s))");
        dialog.setRows(rows);
        store(s -> s.append("Tests: " + failed.size() + " failed, " + passed.size() + " passed"));
    }

    void profiler() {
        var dialog = ListDialog.open(host.anchor(), "Handler profiler", false, 700, 300);
        Runnable[] load = new Runnable[1];
        load[0] = () -> {
            dialog.setMessage("Loading ...");
            store(s -> s.tool("profile", new JsonObject(), reply -> postUi(() -> showProfile(dialog, reply))));
        };
        dialog.addAction("Refresh", () -> load[0].run());
        dialog.addAction("Reset", () -> {
            JsonObject args = new JsonObject();
            args.addProperty("reset", true);
            store(s -> s.tool("profile", args, reply -> postUi(() -> showProfile(dialog, reply))));
        });
        load[0].run();
    }

    private void showProfile(ListDialog dialog, JsonObject reply) {
        if (reply.has("error")) {
            dialog.setMessage(reply.get("error").getAsString());
            return;
        }
        List<ListDialog.Row> rows = new ArrayList<>();
        for (JsonElement e : reply.getAsJsonArray("rows")) {
            JsonObject r = e.getAsJsonObject();
            double avg = r.get("avg_ms").getAsDouble();
            int errors = r.get("errors").getAsInt();
            var row = ListDialog.Row.info(r.get("name").getAsString() + "   [" + r.get("owner").getAsString() + "]",
                    String.format(Locale.ROOT, "%d call(s)  ·  total %.2f ms  ·  avg %.3f ms  ·  slowest %.3f ms%s",
                            r.get("calls").getAsInt(), r.get("total_ms").getAsDouble(), avg, r.get("max_ms").getAsDouble(),
                            errors > 0 ? "  ·  " + errors + " error(s)" : ""));
            rows.add(errors > 0 ? row.colored(R.attr.colorError) : avg >= 5 ? row.colored(R.attr.colorTertiary) : row);
        }
        dialog.setMessage(rows.isEmpty() ? "No handler or timer has run yet. Trigger the events, then Refresh."
                : "Sorted by total time. Amber = average over 5 ms (a tick is 50 ms), red = raised errors."
                + (reply.has("rowsCut") ? " List shortened." : ""));
        dialog.setRows(rows);
    }

    // ------------------------------------------------------------------
    // 44 event tracer (bottom panel)
    // ------------------------------------------------------------------

    View createEventsPane(Context context) {
        var pane = new LinearLayout(context);
        pane.setOrientation(LinearLayout.VERTICAL);
        var header = row(context);
        header.setPadding(header.dp(8), header.dp(2), header.dp(8), header.dp(2));
        traceToggle = smallButton(context, "Start tracing", "Record the events the game fires from now on", v -> toggleTrace());
        header.addView(traceToggle);
        header.addView(smallButton(context, "Clear", "Forget the recorded events", v -> {
            traceLines.clear();
            renderTrace();
            sendTrace("clear");
        }));
        traceStatus = label(context, "", R.attr.textAppearanceBodySmall);
        traceStatus.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
        traceStatus.setPadding(header.dp(10), 0, 0, 0);
        header.addView(traceStatus);
        pane.addView(header);

        traceText = new PlainText(context);
        traceText.setTypeface(Typeface.MONOSPACED);
        traceText.setTextSize(12);
        traceText.setTextColor(IdeColors.TEXT);
        traceText.setTextIsSelectable(true);
        traceText.setPadding(traceText.dp(8), traceText.dp(2), traceText.dp(8), traceText.dp(2));
        traceScroll = new ScrollView(context);
        traceScroll.addView(traceText, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        pane.addView(traceScroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
        renderTrace();
        return pane;
    }

    private static TextView smallButton(Context context, String text, String tooltip, View.OnClickListener onClick) {
        var view = label(context, text, R.attr.textAppearanceLabelLarge);
        view.setPadding(view.dp(10), view.dp(6), view.dp(10), view.dp(6));
        view.setClickable(true);
        view.setTooltipText(tooltip);
        hoverBackground(view);
        view.setOnClickListener(onClick);
        return view;
    }

    // ---- Debugger: breakpoints record the stack and variables; the game never pauses (see _debug in worker.py)

    private LinearLayout debugList;
    private TextView debugStatus;
    private boolean debugVisible;
    private long debugLast;
    private final List<JsonObject> debugHits = new ArrayList<>();

    View createDebugPane(Context context) {
        var pane = new LinearLayout(context);
        pane.setOrientation(LinearLayout.VERTICAL);
        var header = row(context);
        header.setPadding(header.dp(8), header.dp(2), header.dp(8), header.dp(2));
        header.addView(smallButton(context, "Clear", "Forget the recorded hits", v -> {
            debugHits.clear();
            renderDebug();
            sendDebug("clear");
        }));
        header.addView(smallButton(context, "Remove breakpoints", "Remove every breakpoint of this project", v -> {
            host.clearBreakpoints();
            syncBreakpoints();
        }));
        debugStatus = label(context, "", R.attr.textAppearanceBodySmall);
        debugStatus.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
        debugStatus.setPadding(header.dp(10), 0, 0, 0);
        header.addView(debugStatus);
        pane.addView(header);
        debugList = new LinearLayout(context);
        debugList.setOrientation(LinearLayout.VERTICAL);
        debugList.setPadding(debugList.dp(8), debugList.dp(2), debugList.dp(8), debugList.dp(2));
        var scroll = new ScrollView(context);
        scroll.addView(debugList, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        pane.addView(scroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
        renderDebug();
        return pane;
    }

    void debugPanelShown() {
        debugVisible = true;
        sendDebug("sync");
    }

    void debugPanelHidden() {
        debugVisible = false;
    }

    /** Sends the project's breakpoints to the worker (after a toggle, before a run; the worker forgets them on restart). */
    void syncBreakpoints() {
        sendDebug("sync");
    }

    /** A plain Run mutes the breakpoints (nothing records); Debug turns them on. */
    void syncBreakpoints(boolean active) {
        breakpointsActive = active;
        sendDebug("sync");
    }

    private boolean breakpointsActive = true;

    private void sendDebug(String cmd) {
        if (host.projectId() == null || debugList == null) {
            return;
        }
        JsonObject args = new JsonObject();
        args.addProperty("cmd", cmd);
        args.addProperty("since", debugLast);
        JsonObject points = new JsonObject();
        (breakpointsActive ? host.breakpoints() : java.util.Map.<String, java.util.TreeSet<Integer>>of()).forEach((file, lines) -> {
            JsonArray list = new JsonArray();
            lines.forEach(list::add);
            points.add(file, list);
        });
        args.add("breakpoints", points);
        View view = debugList;
        store(s -> s.tool("debug", args, reply -> view.post(() -> onDebug(reply))));
    }

    private void onDebug(JsonObject reply) {
        if (reply.has("error")) {
            debugStatus.setText(reply.get("error").getAsString());
            return;
        }
        int count = reply.has("breakpoints") ? reply.get("breakpoints").getAsInt() : 0;
        for (JsonElement e : reply.getAsJsonArray("hits")) {
            debugHits.add(0, e.getAsJsonObject());
        }
        while (debugHits.size() > 100) {
            debugHits.remove(debugHits.size() - 1);
        }
        debugLast = reply.has("last") ? reply.get("last").getAsLong() : debugLast;
        debugStatus.setText(count == 0 ? "No breakpoints: Ctrl+F8 on a line adds one."
                : count + " breakpoint(s). The game does not pause: each hit records the call stack and the variables.");
        renderDebug();
        if (debugVisible && count > 0) {
            debugList.postDelayed(() -> {
                if (debugVisible) {
                    sendDebug("hits");
                }
            }, 1000);
        }
    }

    private void renderDebug() {
        if (debugList == null) {
            return;
        }
        debugList.removeAllViews();
        Context context = debugList.getContext();
        if (debugHits.isEmpty()) {
            var none = label(context, "No hits yet. Put a breakpoint on a line (Ctrl+F8), then run the project or do what"
                    + " triggers that code in the game.", R.attr.textAppearanceBodySmall);
            none.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
            debugList.addView(none);
            return;
        }
        for (JsonObject hit : debugHits) {
            String file = hit.get("file").getAsString();
            int line = hit.get("line").getAsInt();
            var title = label(context, hit.get("time").getAsString() + "   " + file + ":" + (line + 1) + "   in "
                    + hit.get("function").getAsString() + "()   hit " + hit.get("hit").getAsInt(), R.attr.textAppearanceLabelLarge);
            title.setTextColor(IdeColors.TEXT);
            title.setPadding(0, title.dp(6), 0, title.dp(2));
            title.setClickable(true);
            hoverBackground(title);
            title.setOnClickListener(v -> host.openAt(file, line));
            debugList.addView(title);
            for (JsonElement v : hit.getAsJsonArray("variables")) {
                JsonArray row = v.getAsJsonArray();
                var variable = label(context, "    " + row.get(0).getAsString() + " = " + row.get(2).getAsString()
                        + "    (" + row.get(1).getAsString() + ")", R.attr.textAppearanceBodySmall);
                variable.setTypeface(Typeface.MONOSPACED);
                variable.setTextIsSelectable(true);
                variable.setTextColor(IdeColors.TEXT);
                debugList.addView(variable);
            }
            for (JsonElement f : hit.getAsJsonArray("stack")) {
                JsonObject frame = f.getAsJsonObject();
                String frameFile = frame.get("file").getAsString();
                int frameLine = frame.get("line").getAsInt();
                var call = label(context, "    at " + frame.get("function").getAsString() + "  " + frameFile + ":" + (frameLine + 1),
                        R.attr.textAppearanceBodySmall);
                call.setTextColor(IdeColors.TEXT_SECONDARY);
                call.setClickable(true);
                hoverBackground(call);
                call.setOnClickListener(v -> host.openAt(frameFile, frameLine));
                debugList.addView(call);
            }
        }
    }

    void eventsPanelShown() {
        traceVisible = true;
        sendTrace("get");
    }

    void eventsPanelHidden() {
        traceVisible = false;
    }

    private void toggleTrace() {
        sendTrace(traceEnabled ? "stop" : "start");
    }

    private void sendTrace(String cmd) {
        JsonObject args = new JsonObject();
        args.addProperty("cmd", cmd);
        args.addProperty("since", traceLast);
        View view = traceText;
        if (view == null) {
            return;
        }
        store(s -> s.tool("trace", args, reply -> view.post(() -> onTrace(reply))));
    }

    private void onTrace(JsonObject reply) {
        if (traceText == null) {
            return;
        }
        if (reply.has("error")) {
            traceStatus.setText(reply.get("error").getAsString());
            return;
        }
        traceEnabled = reply.get("enabled").getAsBoolean();
        traceToggle.setText(traceEnabled ? "Stop tracing" : "Start tracing");
        traceStatus.setText(traceEnabled ? "tracing - events appear below" : "off");
        for (JsonElement e : reply.getAsJsonArray("rows")) {
            JsonObject r = e.getAsJsonObject();
            long seq = r.get("seq").getAsLong();
            // A row is re-sent until it is finished; replace instead of appending twice.
            String prefix = seq + "|";
            String time = TIME.format(LocalTime.ofInstant(java.time.Instant.ofEpochMilli(r.get("t").getAsLong()),
                    ZoneId.systemDefault()));
            boolean handled = r.get("handled").getAsBoolean();
            int handlers = r.get("handlers").getAsInt();
            String result = !handled ? "no @on handler for this event"
                    : handlers < 0 ? "running ..."
                    : handlers == 0 ? "handlers did not match the filters"
                    : handlers + " handler(s), " + String.format(Locale.ROOT, "%.2f", r.get("ms").getAsDouble()) + " ms";
            String line = prefix + time + "  " + r.get("event").getAsString() + "  " + r.get("summary").getAsString()
                    + "  → " + result;
            traceLines.removeIf(existing -> existing.startsWith(prefix));
            traceLines.add(line);
        }
        traceLast = reply.get("last").getAsLong();
        traceLines.sort(Comparator.comparingLong(l -> Long.parseLong(l.substring(0, l.indexOf('|')))));
        while (traceLines.size() > TRACE_LINES) {
            traceLines.remove(0);
        }
        renderTrace();
        if (traceEnabled && traceVisible) {
            traceText.postDelayed(() -> {
                if (traceVisible && traceEnabled) {
                    sendTrace("get");
                }
            }, 800);
        }
    }

    private void renderTrace() {
        if (traceText == null) {
            return;
        }
        boolean atBottom = !traceScroll.canScrollVertically(1);
        StringBuilder out = new StringBuilder();
        for (String line : traceLines) {
            out.append(line.substring(line.indexOf('|') + 1)).append('\n');
        }
        if (traceLines.isEmpty()) {
            out.append("Start tracing, then play. Events that no @on handler wants are listed too -\n")
                    .append("if your event is missing here the game never fired it.");
        }
        traceText.setText(out.toString());
        if (atBottom) {
            traceScroll.post(() -> traceScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    // ------------------------------------------------------------------
    // 53 copy diagnostics / helpers
    // ------------------------------------------------------------------

    static void copyToClipboard(String text) {
        store(s -> IdeHost.get().setClipboard(text));
    }

    /** The content id on the caret line or under the caret ({@code ruby_ore = Block("ruby_ore"...)} -> ruby_ore). */
    String contentIdAtCaret() {
        String text = host.text();
        int caret = Math.max(0, Math.min(host.caret(), text.length()));
        int line = TextOps.lineOf(text, caret);
        String word = "";
        int[] range = TextOps.wordAt(text, caret);
        if (range != null) {
            word = text.substring(range[0], range[1]);
        }
        String onLine = "";
        for (PyOutline.Symbol s : PyOutline.parse(text)) {
            if (s.kind() != PyOutline.Kind.CONTENT) {
                continue;
            }
            if (s.line() == line) {
                onLine = s.contentId();
            }
            if (onLine.isEmpty() && (s.name().equals(word) || s.contentId().equals(word))) {
                onLine = s.contentId();
            }
        }
        return onLine.isEmpty() ? word : onLine;
    }
}
