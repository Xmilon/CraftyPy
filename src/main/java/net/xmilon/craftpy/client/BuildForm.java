package net.xmilon.craftpy.client;

import java.util.Set;
import java.util.regex.Pattern;

/** Validation and request text of the "Build module" dialog; no UI in here. */
public final class BuildForm {
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z._+-]{0,31}");

    private BuildForm() {
    }

    /** Null when the form can be sent, otherwise what to tell the user. */
    public static String error(boolean asNew, String version, String newId, Set<String> installedIds) {
        if (!VERSION.matcher(version.trim()).matches()) {
            return "Version: letters, digits and . _ + - (for example 1.2.0)";
        }
        if (asNew) {
            String id = newId.trim();
            if (!ID.matcher(id).matches()) {
                return "Module id: start with a letter, then lowercase letters, digits or _ (at most 64)";
            }
            if (installedIds.contains(id)) {
                return "A module with the id '" + id + "' is already installed - choose another id or update it";
            }
        }
        return null;
    }

    /** The JSON the server's BUILD action reads. */
    public static String request(boolean asNew, String version, String newId, String newName, boolean install) {
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("version", version.trim());
        json.addProperty("mode", asNew ? "new" : "update");
        json.addProperty("newId", newId.trim());
        json.addProperty("newName", newName.trim());
        json.addProperty("install", install);
        return json.toString();
    }
}
