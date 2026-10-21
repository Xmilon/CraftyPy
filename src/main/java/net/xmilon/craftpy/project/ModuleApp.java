package net.xmilon.craftpy.project;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One entry of the Hub, declared in {@code project.json} / {@code module.json}:
 * {@code "apps": [{"id": "journal", "name": "Task Journal", "window": "journal", "icon": "icon.png", ...}]}.
 * Plain data shared by the server (which pushes the list) and the client (which draws it).
 */
public class ModuleApp {
    public static final int MAX_APPS = 32;
    public static final int MAX_NAME = 32;
    public static final int MAX_DESCRIPTION = 120;
    public static final List<String> CATEGORIES = List.of("Tools", "Games", "Server", "Media", "Machinery");
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]{1,64}");

    public String id = "";
    public String name = "";
    public String description = "";
    /** Id of the window ({@code ui/<id>.json} or a {@code @window}) the app opens. */
    public String window = "";
    /** An item id ({@code minecraft:book}) or a PNG under {@code assets/} ({@code icon.png}); empty = a letter. */
    public String icon = "";
    public String category = "Tools";
    public int order = 0;

    /** The apps that survived {@link #clean}, and why the others did not. */
    public record Cleaned(List<ModuleApp> apps, List<String> problems) { }

    /**
     * Validates what a manifest declared: limits, ids, the first of two equal ids wins. Never throws, so a hand-edited
     * file cannot stop a module from loading; the problems are shown on the Modules page.
     */
    public static Cleaned clean(List<ModuleApp> raw) {
        List<ModuleApp> apps = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (raw == null) {
            return new Cleaned(apps, problems);
        }
        for (ModuleApp app : raw) {
            if (app == null) {
                continue;
            }
            if (apps.size() >= MAX_APPS) {
                problems.add("More than " + MAX_APPS + " apps: the rest are ignored");
                break;
            }
            String id = app.id == null ? "" : app.id.strip().toLowerCase(Locale.ROOT);
            if (!ID.matcher(id).matches()) {
                problems.add("App id '" + (app.id == null ? "" : app.id) + "' is not valid (a-z, 0-9, _ . -, up to 64)");
                continue;
            }
            if (!seen.add(id)) {
                problems.add("App id '" + id + "' is declared twice: the first one is used");
                continue;
            }
            String window = app.window == null ? "" : app.window.strip();
            if (window.isEmpty() || window.length() > 128) {
                problems.add("App '" + id + "' names no window to open");
                continue;
            }
            ModuleApp out = new ModuleApp();
            out.id = id;
            out.window = window;
            out.name = limit(app.name == null || app.name.isBlank() ? id : app.name.strip(), MAX_NAME);
            out.description = limit(app.description == null ? "" : app.description.strip(), MAX_DESCRIPTION);
            out.icon = icon(app.icon);
            out.category = CATEGORIES.stream().filter(c -> c.equalsIgnoreCase(app.category == null ? "" : app.category.strip()))
                    .findFirst().orElse("Tools");
            out.order = Math.max(-1000, Math.min(1000, app.order));
            apps.add(out);
        }
        return new Cleaned(apps, problems);
    }

    /**
     * An icon reference as stored: an item id, or a relative path inside {@code assets/} (no {@code ..}, no drive,
     * ends in .png). Anything else is dropped, and the Hub draws a letter instead.
     */
    public static String icon(String value) {
        if (value == null) {
            return "";
        }
        String icon = value.strip().replace('\\', '/');
        if (icon.startsWith("assets/")) {
            icon = icon.substring("assets/".length());
        }
        if (icon.isEmpty() || icon.length() > 128 || icon.contains("..") || icon.startsWith("/")) {
            return "";
        }
        if (icon.toLowerCase(Locale.ROOT).endsWith(".png")) {
            return icon.matches("[A-Za-z0-9_./-]+") ? icon.toLowerCase(Locale.ROOT) : "";
        }
        return icon.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") ? icon : "";
    }

    public static boolean isItem(String icon) {
        return icon != null && icon.contains(":");
    }

    private static String limit(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
