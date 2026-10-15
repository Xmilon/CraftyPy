package net.xmilon.craftpy.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.xmilon.craftpy.analysis.ProjectScan;
import net.xmilon.craftpy.catalog.ModCatalog;
import net.xmilon.craftpy.analysis.PyOutline;
import net.xmilon.craftpy.lifecycle.EventTrace;
import net.xmilon.craftpy.network.ProjectAction;
import net.xmilon.craftpy.network.ResponsePayload;
import net.xmilon.craftpy.project.ProjectManager;
import net.xmilon.craftpy.python.Declarations;
import net.xmilon.craftpy.python.PyRuntime;
import net.xmilon.craftpy.registry.SlotRegistry;
import net.xmilon.craftpy.util.Json;

/**
 * The {@link ProjectAction#TOOL} requests: project-wide editor tools (symbols, asset usage, rename,
 * "why isn't it showing") and the runtime tools (declarations for previews, handler profiler, event
 * tracer). Every reply is a JSON object that echoes {@code "op"} so the client can route it, and is kept
 * below the 32k-character packet string limit.
 */
public final class ProjectTools {
    /** ByteBufCodecs.STRING_UTF8 allows 32767 characters; stay well below. */
    private static final int MAX_JSON = 30_000;

    private ProjectTools() {
    }

    public static CompletableFuture<ResponsePayload> handle(ProjectManager manager, String projectId, String data) {
        JsonObject request;
        try {
            request = JsonParser.parseString(data).getAsJsonObject();
        } catch (RuntimeException e) {
            return done(error("", "Malformed tool request"));
        }
        String op = string(request, "op");
        if (!ProjectManager.isValidId(projectId) && !op.equals("trace") && !op.equals("profile")) {
            return done(error(op, "Invalid project id"));
        }
        return switch (op) {
            case "symbols" -> manager.snapshot(projectId).thenApply(ProjectTools::symbols);
            case "usage" -> manager.snapshot(projectId).thenApply(ProjectTools::usage);
            case "rename" -> rename(manager, projectId, request);
            case "explain" -> manager.snapshot(projectId).thenApply(s -> explain(projectId, string(request, "id"), s));
            case "decls" -> done(decls(projectId));
            case "profile" -> profile(request);
            case "trace" -> done(trace(request));
            case "complete", "hover", "signature" -> assist(op, projectId, request);
            case "changes" -> done(changes(projectId));
            case "add_part" -> addPart(manager, projectId, request);
            case "grep" -> manager.snapshot(projectId).thenApply(s -> grep(s, string(request, "q"),
                    request.has("todo") && request.get("todo").getAsBoolean()));
            default -> done(error(op, "Unknown tool: " + op));
        };
    }

    // -- find in files / TODO list -----------------------------------------------------

    private static final java.util.regex.Pattern TODO = java.util.regex.Pattern.compile("#\\s*(TODO|FIXME|XXX|HACK)\\b.*",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final int GREP_LIMIT = 500;

    /** Lines of the project's text files that contain {@code query} (case-insensitive), or TODO comments. */
    static ResponsePayload grep(ProjectManager.Snapshot snapshot, String query, boolean todo) {
        JsonObject out = new JsonObject();
        out.addProperty("op", "grep");
        JsonArray rows = new JsonArray();
        String needle = query.toLowerCase(java.util.Locale.ROOT);
        if (!todo && needle.isBlank()) {
            out.add("rows", rows);
            return ok(out);
        }
        boolean cut = false;
        search:
        for (var entry : snapshot.texts().entrySet()) {
            String[] lines = entry.getValue().split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                boolean hit = todo ? TODO.matcher(lines[i]).find() : lines[i].toLowerCase(java.util.Locale.ROOT).contains(needle);
                if (!hit) {
                    continue;
                }
                if (rows.size() >= GREP_LIMIT) {
                    cut = true;
                    break search;
                }
                JsonObject row = new JsonObject();
                row.addProperty("f", entry.getKey());
                row.addProperty("l", i);
                row.addProperty("t", clip(lines[i]));
                rows.add(row);
            }
        }
        out.add("rows", rows);
        out.addProperty("cut", cut);
        return ok(fit(out, "rows"));
    }

    // -- building blocks: a new file connected to main.py ----------------------------

    /**
     * Writes {@code code/<module>.py} and, with {@code patchMain}, adds {@code import <module>} to {@code code/main.py}.
     * The editor sends {@code patchMain=false} when main.py is open in it: it patches its own buffer instead, so an unsaved
     * main.py is never overwritten here.
     */
    private static CompletableFuture<ResponsePayload> addPart(ProjectManager manager, String projectId, JsonObject request) {
        String module = string(request, "module");
        String content = string(request, "content");
        boolean patchMain = request.has("patchMain") && request.get("patchMain").getAsBoolean();
        if (!module.matches("[a-z_][a-z0-9_]{0,63}")) {
            return done(error("add_part", "Not a valid file name: " + module + " (lowercase letters, digits and _)"));
        }
        String path = "code/" + module + ".py";
        return manager.snapshot(projectId).thenCompose(snapshot -> {
            if (snapshot.paths().contains(path)) {
                return done(error("add_part", path + " already exists"));
            }
            return manager.writeFile(projectId, path, content).thenCompose(written -> {
                if (!written.success()) {
                    return done(error("add_part", written.message()));
                }
                JsonObject out = new JsonObject();
                out.addProperty("op", "add_part");
                out.addProperty("path", path);
                String main = snapshot.texts().get("code/main.py");
                if (!patchMain || main == null) {
                    out.addProperty("main", false);
                    return done(ok(out));
                }
                String patched = net.xmilon.craftpy.analysis.PyImports.addImport(main, module);
                if (patched.equals(main)) {
                    out.addProperty("main", true);
                    return done(ok(out));
                }
                return manager.writeFile(projectId, "code/main.py", patched).thenApply(result -> {
                    out.addProperty("main", result.success());
                    return ok(out);
                });
            });
        });
    }

    // -- symbols (21) -------------------------------------------------------------

    private static ResponsePayload symbols(ProjectManager.Snapshot snapshot) {
        JsonObject out = new JsonObject();
        out.addProperty("op", "symbols");
        JsonArray rows = new JsonArray();
        for (var entry : snapshot.texts().entrySet()) {
            if (!entry.getKey().endsWith(".py")) {
                continue;
            }
            for (PyOutline.Symbol s : PyOutline.parse(entry.getValue())) {
                JsonObject row = new JsonObject();
                row.addProperty("n", s.qualifiedName());
                row.addProperty("k", s.kind().name());
                row.addProperty("f", entry.getKey());
                row.addProperty("l", s.line());
                row.addProperty("d", s.detail());
                rows.add(row);
            }
        }
        out.add("rows", rows);
        return ok(fit(out, "rows"));
    }

    // -- asset usage (48) ---------------------------------------------------------

    private static ResponsePayload usage(ProjectManager.Snapshot snapshot) {
        ProjectScan.UsageReport report = ProjectScan.assetUsage(snapshot.paths(), snapshot.texts());
        JsonObject out = new JsonObject();
        out.addProperty("op", "usage");
        JsonArray assets = new JsonArray();
        for (ProjectScan.AssetUse use : report.assets()) {
            JsonObject row = new JsonObject();
            row.addProperty("path", use.path());
            row.addProperty("count", use.references().size());
            JsonArray refs = new JsonArray();
            for (ProjectScan.Reference ref : use.references().subList(0, Math.min(4, use.references().size()))) {
                refs.add(ref.file() + ":" + (ref.line() + 1));
            }
            row.add("refs", refs);
            assets.add(row);
        }
        out.add("assets", assets);
        JsonArray missing = new JsonArray();
        for (ProjectScan.MissingAsset m : report.missing()) {
            JsonObject row = new JsonObject();
            row.addProperty("value", m.value());
            row.addProperty("file", m.file());
            row.addProperty("line", m.line());
            missing.add(row);
        }
        out.add("missing", missing);
        return ok(fit(fit(out, "assets"), "missing"));
    }

    // -- rename a content id (33) ----------------------------------------------------

    private static CompletableFuture<ResponsePayload> rename(ProjectManager manager, String projectId, JsonObject request) {
        String from = string(request, "from");
        String to = string(request, "to");
        boolean apply = request.has("apply") && request.get("apply").getAsBoolean();
        return manager.snapshot(projectId).thenCompose(snapshot -> {
            ProjectScan.RenamePlan plan = ProjectScan.renameId(snapshot.paths(), snapshot.texts(), from, to);
            JsonObject out = new JsonObject();
            out.addProperty("op", "rename");
            out.addProperty("from", from);
            out.addProperty("to", to);
            if (!plan.ok()) {
                out.addProperty("error", plan.error());
                return done(ok(out));
            }
            JsonArray edits = new JsonArray();
            for (ProjectScan.Edit edit : plan.edits()) {
                JsonObject row = new JsonObject();
                row.addProperty("file", edit.file());
                row.addProperty("line", edit.line());
                row.addProperty("before", clip(edit.before()));
                row.addProperty("after", clip(edit.after()));
                edits.add(row);
            }
            out.add("edits", edits);
            JsonArray moves = new JsonArray();
            for (ProjectScan.Move move : plan.moves()) {
                moves.add(move.from() + "  ->  " + move.to());
            }
            out.add("moves", moves);
            JsonArray warnings = new JsonArray();
            plan.warnings().forEach(warnings::add);
            out.add("warnings", warnings);
            if (!apply) {
                out.addProperty("applied", false);
                return done(ok(fit(out, "edits")));
            }
            return manager.applyRename(projectId, plan).thenApply(result -> {
                out.addProperty("applied", result.success());
                out.addProperty("message", result.message());
                return ok(fit(out, "edits"));
            });
        });
    }

    // -- "why isn't my block showing" (49) ------------------------------------------------

    private static ResponsePayload explain(String projectId, String id, ProjectManager.Snapshot snapshot) {
        JsonArray checks = new JsonArray();
        PyOutline.Symbol decl = null;
        String declFile = null;
        List<String> known = new ArrayList<>();
        for (var entry : snapshot.texts().entrySet()) {
            if (!entry.getKey().endsWith(".py")) {
                continue;
            }
            for (PyOutline.Symbol s : PyOutline.parse(entry.getValue())) {
                if (s.kind() == PyOutline.Kind.CONTENT) {
                    known.add(s.contentId());
                    if (s.contentId().equals(id) && decl == null) {
                        decl = s;
                        declFile = entry.getKey();
                    }
                }
            }
        }
        if (decl == null) {
            String near = nearest(id, known);
            check(checks, "error", "No Block(\"" + id + "\") or Item(\"" + id + "\") in this project's code."
                    + (near == null ? "" : " Did you mean '" + near + "'?"));
            return ok(explainReply(id, checks));
        }
        check(checks, "ok", "Declared in " + declFile + " line " + (decl.line() + 1) + ": " + decl.detail() + "(\"" + id + "\", ...)");
        if (!declFile.equals("code/main.py")) {
            check(checks, "error", "Only code/main.py runs; " + declFile + " is never imported, so this declaration does nothing."
                    + " Move it into code/main.py.");
        }
        boolean block = decl.detail().equals("Block");
        String holder = (block ? "BLOCK:" : "ITEM:") + projectId + ":" + id;

        Declarations declarations = SlotRegistry.declarations(projectId);
        if (!SlotRegistry.isProjectActive(projectId) || declarations == null) {
            check(checks, "error", "The project is not running. Press Run (Ctrl+R) - content only exists while its project is loaded.");
            return ok(explainReply(id, checks));
        }
        Declarations.Content content = null;
        for (Declarations.Content c : declarations.contents()) {
            if (c.id().equals(id) && c.kind().equals(block ? "block" : "item")) {
                content = c;
            }
        }
        if (content == null) {
            check(checks, "error", "The last Run did not register '" + id + "'. It is declared inside an if/function that did not execute,"
                    + " or Run happened before you added it - press Run again.");
            return ok(explainReply(id, checks));
        }
        check(checks, "ok", "The last Run registered it.");
        SlotRegistry.SlotRef slot = SlotRegistry.snapshotByHolder().get(holder);
        if (slot == null) {
            check(checks, "error", "No slot was assigned (all slots in use?). Check block_slots / item_slots in the CraftPy config.");
        } else {
            check(checks, "ok", "Slot " + slot.index() + " is assigned (" + (block ? "pcblock_" : "pcitem_") + slot.index() + ").");
        }
        JsonElement texture = content.definition().get("texture");
        String textureName = texture == null || texture.isJsonNull() ? null
                : texture.isJsonPrimitive() ? texture.getAsString()
                : texture.isJsonObject() && texture.getAsJsonObject().has("value") ? texture.getAsJsonObject().get("value").getAsString()
                : null;
        if (textureName == null) {
            check(checks, "warn", "No texture= given, so the game shows its missing-texture (purple/black) look. Add texture=\"...\".");
        } else if (textureName.contains(":")) {
            check(checks, "ok", "Uses the texture " + textureName + " (looked up by the game, not checked here).");
        } else {
            String path = "assets/textures/" + textureName;
            if (snapshot.paths().contains(path)) {
                check(checks, "ok", "Texture file " + path + " exists.");
            } else {
                String near = nearest(textureName, snapshot.paths().stream()
                        .filter(p -> p.startsWith("assets/textures/")).map(p -> p.substring("assets/textures/".length())).toList());
                check(checks, "error", "Texture file " + path + " does not exist." + (near == null ? "" : " Did you mean '" + near + "'?"));
            }
        }
        check(checks, "info", "If the texture or model looks stale, the resource pack reloads after Run; F3+T forces it.");
        check(checks, "info", "Get it with: /craftpy give @s " + projectId + ":" + id + (block ? " block" : ""));
        return ok(explainReply(id, checks));
    }

    private static JsonObject explainReply(String id, JsonArray checks) {
        JsonObject out = new JsonObject();
        out.addProperty("op", "explain");
        out.addProperty("id", id);
        out.add("checks", checks);
        return out;
    }

    private static void check(JsonArray checks, String level, String text) {
        JsonObject row = new JsonObject();
        row.addProperty("level", level);
        row.addProperty("text", text);
        checks.add(row);
    }

    // -- declarations for previews (43, 46, 47) --------------------------------------------

    private static ResponsePayload decls(String projectId) {
        JsonObject out = new JsonObject();
        out.addProperty("op", "decls");
        Declarations declarations = SlotRegistry.declarations(projectId);
        out.addProperty("loaded", declarations != null && SlotRegistry.isProjectActive(projectId));
        if (declarations == null) {
            return ok(out);
        }
        Map<String, SlotRegistry.SlotRef> slots = SlotRegistry.snapshotByHolder();
        JsonArray contents = new JsonArray();
        JsonArray recipes = new JsonArray();
        JsonArray loot = new JsonArray();
        for (Declarations.Content c : declarations.contents()) {
            if (!c.kind().equals("block") && !c.kind().equals("item")) {
                continue;
            }
            JsonObject row = new JsonObject();
            row.addProperty("kind", c.kind());
            row.addProperty("id", c.id());
            row.add("def", c.definition());
            SlotRegistry.SlotRef slot = slots.get((c.kind().equals("block") ? "BLOCK:" : "ITEM:") + projectId + ":" + c.id());
            row.addProperty("slot", slot == null ? -1 : slot.index());
            contents.add(row);
        }
        for (Declarations.DatapackEntry entry : declarations.datapack()) {
            if (entry.kind().equals("recipe")) {
                recipes.add(entry.payload());
            } else if (entry.kind().equals("loot_table")) {
                loot.add(entry.payload());
            }
        }
        out.add("contents", contents);
        out.add("recipes", recipes);
        out.add("loot", loot);
        return ok(fit(fit(fit(out, "contents"), "recipes"), "loot"));
    }

    // -- profiler (40) and tracer (44) ---------------------------------------------------------

    private static CompletableFuture<ResponsePayload> profile(JsonObject request) {
        if (!PyRuntime.available()) {
            return done(error("profile", "No Python runtime"));
        }
        boolean reset = request.has("reset") && request.get("reset").getAsBoolean();
        return PyRuntime.get().profile(reset).thenApply(result -> {
            result.addProperty("op", "profile");
            return ok(fit(result, "rows"));
        });
    }

    private static ResponsePayload changes(String projectId) {
        JsonObject body = new JsonObject();
        body.addProperty("op", "changes");
        JsonArray rows = new JsonArray();
        for (String line : net.xmilon.craftpy.project.ChangeLog.read(projectId, 300)) {
            rows.add(line);
        }
        body.add("rows", rows);
        return ok(fit(body, "rows"));
    }

    /** {@code name=} entries for the arguments the call accepts and the typed part matches, in front of the other items. */
    /** Items and blocks the project declares (as {@code project:id}), by kind; blocks also exist as items. */
    private static Map<String, List<String>> ownIds(String projectId) {
        Map<String, List<String>> own = new java.util.HashMap<>();
        Declarations declarations = SlotRegistry.declarations(projectId);
        if (declarations != null) {
            for (Declarations.Content content : declarations.contents()) {
                String id = projectId + ":" + content.id();
                if (content.kind().equals("item") || content.kind().equals("block")) {
                    own.computeIfAbsent(content.kind(), k -> new java.util.ArrayList<>()).add(id);
                }
                if (content.kind().equals("block")) {
                    own.computeIfAbsent("item", k -> new java.util.ArrayList<>()).add(id);
                }
            }
        }
        return own;
    }

    private static CompletableFuture<ResponsePayload> assist(String op, String projectId, JsonObject request) {
        String code = string(request, "code");
        int line;
        int column;
        try {
            line = request.has("line") ? request.get("line").getAsInt() : 0;
            column = request.has("column") ? request.get("column").getAsInt() : 0;
        } catch (RuntimeException e) {
            return done(error(op, "Bad position"));
        }
        if (op.equals("complete")) {
            // inside a string the ids of the installed mods are offered, no Python needed
            JsonObject local = CompletionEngine.complete(code, line, column, ModCatalog.get().view(), ownIds(projectId));
            if (local != null) {
                local.addProperty("op", op);
                if (request.has("seq")) {
                    local.add("seq", request.get("seq"));
                }
                return done(ok(local));
            }
        }
        if (!PyRuntime.available()) {
            return done(error(op, "No Python runtime"));
        }
        // the worker infers types (chained calls, event handlers, keyword arguments of the call being typed)
        return PyRuntime.get().assist(op, code, line, column).thenApply(result -> {
            result.addProperty("op", op);
            if (request.has("seq")) {
                result.add("seq", request.get("seq"));
            }
            return ok(result);
        });
    }

    private static ResponsePayload trace(JsonObject request) {
        String cmd = string(request, "cmd");
        switch (cmd) {
            case "start" -> EventTrace.setEnabled(true);
            case "stop" -> EventTrace.setEnabled(false);
            case "clear" -> EventTrace.clear();
            default -> {
            }
        }
        long since = request.has("since") ? request.get("since").getAsLong() : 0;
        JsonObject out = new JsonObject();
        out.addProperty("op", "trace");
        out.addProperty("enabled", EventTrace.enabled());
        JsonArray rows = new JsonArray();
        long last = since;
        for (EventTrace.Entry e : EventTrace.since(since)) {
            JsonObject row = new JsonObject();
            row.addProperty("seq", e.seq());
            row.addProperty("t", e.timeMs());
            row.addProperty("event", e.event());
            row.addProperty("summary", e.summary());
            row.addProperty("handled", e.handled());
            row.addProperty("handlers", e.handlers());
            row.addProperty("ms", e.ms());
            rows.add(row);
            last = Math.max(last, e.seq());
        }
        out.add("rows", rows);
        out.addProperty("last", last);
        return ok(fitFromStart(out, "rows"));
    }

    // -- helpers ---------------------------------------------------------------------------------

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    private static String clip(String text) {
        return text.length() > 160 ? text.substring(0, 160) + "..." : text;
    }

    private static CompletableFuture<ResponsePayload> done(ResponsePayload payload) {
        return CompletableFuture.completedFuture(payload);
    }

    private static ResponsePayload ok(JsonObject body) {
        return new ResponsePayload(ProjectAction.TOOL, true, "", Json.COMPACT.toJson(body));
    }

    private static ResponsePayload error(String op, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("op", op);
        return new ResponsePayload(ProjectAction.TOOL, false, message, Json.COMPACT.toJson(body));
    }

    /** Drops rows from the end of {@code key} until the JSON fits; sets {@code <key>Cut} to the number dropped. */
    static JsonObject fit(JsonObject body, String key) {
        return trim(body, key, false);
    }

    /** Like {@link #fit} but keeps the newest rows (drops from the front) - for the tracer. */
    static JsonObject fitFromStart(JsonObject body, String key) {
        return trim(body, key, true);
    }

    private static JsonObject trim(JsonObject body, String key, boolean fromFront) {
        if (!body.has(key) || !body.get(key).isJsonArray()) {
            return body;
        }
        JsonArray array = body.getAsJsonArray(key);
        int cut = 0;
        while (array.size() > 0 && Json.COMPACT.toJson(body).length() > MAX_JSON) {
            int chunk = Math.max(1, array.size() / 20);
            for (int i = 0; i < chunk; i++) {
                array.remove(fromFront ? 0 : array.size() - 1);
            }
            cut += chunk;
        }
        if (cut > 0) {
            body.addProperty(key + "Cut", body.has(key + "Cut") ? body.get(key + "Cut").getAsInt() + cut : cut);
        }
        return body;
    }

    /** The candidate within edit distance 2 of {@code word}, or null. */
    static String nearest(String word, List<String> candidates) {
        String best = null;
        int bestDistance = 3;
        for (String candidate : candidates) {
            int d = distance(word, candidate);
            if (d < bestDistance) {
                bestDistance = d;
                best = candidate;
            }
        }
        return best;
    }

    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int[] current = new int[b.length() + 1];
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            previous = current;
        }
        return previous[b.length()];
    }
}
