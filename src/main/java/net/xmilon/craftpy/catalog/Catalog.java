package net.xmilon.craftpy.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonObject;

/**
 * What one namespace of one mod offers to a script author: its registry entries, tags, recipe types, events and
 * classes. Written once per mod (and per mod version) to {@code system/catalog/<namespace>.json} by {@code CatalogDump}
 * and read by the IDE for completion and checks. Plain data so Gson can read and write it.
 */
public final class Catalog {
    public int format = 2;
    public String namespace = "";
    /** The mod that owns the namespace ({@code minecraft} for vanilla). The catalog is hidden when this mod is gone. */
    public String mod = "";
    public String version = "";
    public String jarHash = "";
    /** Registry id ({@code item}, {@code mob_effect}, {@code irons_spellbooks:spells}) to this namespace's full ids. */
    public Map<String, List<String>> registries = new TreeMap<>();
    /** Registry id to this namespace's tag ids (without the leading #). */
    public Map<String, List<String>> tags = new TreeMap<>();
    /** Recipe type id ({@code create:mixing}) to what is known of its JSON. */
    public Map<String, RecipeType> recipeTypes = new TreeMap<>();
    /** Names of NeoForge event classes of the mod. */
    public List<String> events = new ArrayList<>();
    /** Public class names of the mod jar, for {@code java.cls("...")}. */
    public List<String> classes = new ArrayList<>();
    /** Datapack folders the mod reads ({@code data/<ns>/<folder>}). */
    public List<String> folders = new ArrayList<>();
    /** Registry/class metadata. Discovery never invokes an entity factory or a goal constructor. */
    public Map<String, JsonObject> capabilities = new TreeMap<>();

    public static final class RecipeType {
        public int count;
        /** Field names seen in recipes of this type (without {@code type}). */
        public List<String> fields = new ArrayList<>();
        public JsonObject sample;
        public String note = "";
        /** {@code dump} (read from the running game) or {@code seed} (shipped with CraftPy). */
        public String source = "";

        public RecipeType() {
        }

        public RecipeType(int count, List<String> fields, JsonObject sample, String note, String source) {
            this.count = count;
            this.fields = fields;
            this.sample = sample;
            this.note = note;
            this.source = source;
        }
    }
}
