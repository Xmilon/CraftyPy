package net.xmilon.craftpy.client;

import net.xmilon.craftpy.CraftPyLog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import net.xmilon.craftpy.util.Json;

/**
 * Editor preferences and per-file UI state (theme, zoom, word wrap, bookmarks, pinned tabs,
 * recent files, find history, panel sizes). Saved to {@code config/craftpy-editor.json}.
 * File keys are {@code "<project>/<path>"}.
 */
public final class EditorSettings {
    public static final int MAX_RECENT = 15;
    public static final int MAX_HISTORY = 20;

    public String theme = EditorTheme.DARK.name();
    public float fontSize = 13;
    public int ruler = 100;
    public boolean trimOnSave = true;
    /** Pin the signatures of the scopes scrolled out of view at the top of the code area. */
    public boolean stickyScroll = true;
    /** The overview strip next to the code. */
    public boolean minimap = true;
    /** Dim everything except the function/class the cursor is in. */
    public boolean focusMode = false;
    public int explorerWidth = 220;
    /** Width of the asset dock (preview / texture / sound editor) at the right of the editor. */
    public int dockWidth = 460;
    /** Share of the editor column the code area takes (the rest is the console/problems panel). */
    public float codeWeight = 0.67f;
    /** IntelliJ-style tool windows: the Project tree on the left and the bottom panel can be closed from their stripes. */
    public boolean explorerVisible = true;
    public boolean bottomVisible = true;
    /** Share of the width the main editor keeps while the editor is split. */
    public float splitWeight = 0.5f;
    public int blocksWidth = 320;
    public boolean indentGuides = true;
    /** Open folders of the explorer, per project. */
    public Map<String, TreeSet<String>> expanded = new LinkedHashMap<>();
    /** Open tabs per project (the active one last), reopened with the project. */
    public Map<String, List<String>> openTabs = new LinkedHashMap<>();
    public Set<String> wrapped = new TreeSet<>();
    public Set<String> pinned = new LinkedHashSet<>();
    public Map<String, TreeSet<Integer>> bookmarks = new LinkedHashMap<>();
    /** Debugger breakpoints by {@link #key(String, String)}, 0-based lines. */
    public Map<String, TreeSet<Integer>> breakpoints = new LinkedHashMap<>();
    public List<String> recent = new ArrayList<>();
    public List<String> findHistory = new ArrayList<>();

    private static EditorSettings instance;

    public static synchronized EditorSettings get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static Path file() {
        return IdeHost.get().configDir().resolve("craftpy-editor.json");
    }

    private static EditorSettings load() {
        try {
            Path file = file();
            if (Files.isRegularFile(file)) {
                EditorSettings loaded = Json.GSON.fromJson(Files.readString(file), EditorSettings.class);
                if (loaded != null) {
                    loaded.fillDefaults();
                    return loaded;
                }
            }
        } catch (Exception e) {
            CraftPyLog.LOGGER.warn("CraftPy: could not read editor settings, using defaults: {}", e.toString());
        }
        return new EditorSettings();
    }

    /** Gson leaves fields missing from older files null. */
    private void fillDefaults() {
        if (theme == null) theme = EditorTheme.DARK.name();
        if (fontSize < 6 || fontSize > 40) fontSize = 13;
        if (wrapped == null) wrapped = new TreeSet<>();
        if (pinned == null) pinned = new LinkedHashSet<>();
        if (bookmarks == null) bookmarks = new LinkedHashMap<>();
        if (breakpoints == null) breakpoints = new LinkedHashMap<>();
        if (recent == null) recent = new ArrayList<>();
        if (findHistory == null) findHistory = new ArrayList<>();
        if (expanded == null) expanded = new LinkedHashMap<>();
        if (openTabs == null) openTabs = new LinkedHashMap<>();
        if (splitWeight < 0.15f || splitWeight > 0.85f) splitWeight = 0.5f;
        if (blocksWidth < 240 || blocksWidth > 1400) blocksWidth = 320;
        if (codeWeight <= 0.1f || codeWeight >= 0.95f) codeWeight = 0.67f;
        if (explorerWidth < 120) explorerWidth = 220;
        if (dockWidth < 280 || dockWidth > 1400) dockWidth = 460;
    }

    public synchronized void save() {
        try {
            Path file = file();
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.GSON.toJson(this));
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            CraftPyLog.LOGGER.warn("CraftPy: could not save editor settings: {}", e.toString());
        }
    }

    public EditorTheme theme() {
        return EditorTheme.byName(theme);
    }

    public static String key(String project, String path) {
        return (project == null ? "" : project) + "/" + path;
    }

    /** Moves a file to the front of the recent list. */
    public synchronized void touchRecent(String project, String path) {
        String key = key(project, path);
        recent.remove(key);
        recent.add(0, key);
        while (recent.size() > MAX_RECENT) {
            recent.remove(recent.size() - 1);
        }
    }

    public synchronized void rememberSearch(String query) {
        if (query == null || query.isBlank()) {
            return;
        }
        findHistory.remove(query);
        findHistory.add(0, query);
        while (findHistory.size() > MAX_HISTORY) {
            findHistory.remove(findHistory.size() - 1);
        }
    }

    public synchronized TreeSet<Integer> bookmarks(String project, String path) {
        return bookmarks.computeIfAbsent(key(project, path), k -> new TreeSet<>());
    }

    public synchronized TreeSet<Integer> breakpoints(String project, String path) {
        return breakpoints.computeIfAbsent(key(project, path), k -> new TreeSet<>());
    }

    /** The breakpoints of a project by file path (files without any are left out). */
    public synchronized Map<String, TreeSet<Integer>> breakpointsOf(String project) {
        Map<String, TreeSet<Integer>> out = new LinkedHashMap<>();
        String prefix = key(project, "");
        breakpoints.forEach((key, lines) -> {
            if (key.startsWith(prefix) && !lines.isEmpty()) {
                out.put(key.substring(prefix.length()), new TreeSet<>(lines));
            }
        });
        return out;
    }

    public synchronized void clearBreakpoints(String project) {
        String prefix = key(project, "");
        breakpoints.keySet().removeIf(key -> key.startsWith(prefix));
    }
}
