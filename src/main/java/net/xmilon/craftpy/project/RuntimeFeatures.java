package net.xmilon.craftpy.project;

import java.util.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import net.neoforged.fml.ModList;

/** Live execution environment, deliberately independent of cached discovery catalogs. */
public final class RuntimeFeatures {
    private RuntimeFeatures() { }
    public static Set<String> mods() {
        Set<String> out = new TreeSet<>(); var list = ModList.get();
        if (list != null) list.getMods().forEach(m -> out.add(m.getModId())); return out;
    }
    public static boolean createAvailable() {
        var list = ModList.get();
        return list != null && list.getModContainerById("create").map(c -> c.getModInfo().getVersion().toString().equals("6.0.10")).orElse(false);
    }
    public static Set<String> capabilities() {
        Set<String> out = new TreeSet<>(Set.of("machine.rotation", "machine.inventory", "machine.energy", "machine.fluid"));
        if (createAvailable()) out.add("rotation.create"); return out;
    }
    public static JsonObject snapshot() {
        JsonObject out = new JsonObject(); out.addProperty("op", "building_blocks"); out.addProperty("ok", true);
        JsonArray mods = new JsonArray(), capabilities = new JsonArray(); mods().forEach(mods::add); capabilities().forEach(capabilities::add);
        out.add("mods", mods); out.add("capabilities", capabilities); return out;
    }
    public static String unavailable(String[] requiredMods, String[] requiredCapabilities) {
        var missing = new ArrayList<String>();
        if ((requiredMods != null && requiredMods.length > 64) || (requiredCapabilities != null && requiredCapabilities.length > 64)) return "Unavailable: too many integration requirements";
        var mods = mods(); var features = capabilities();
        if (requiredMods != null) for (String mod : requiredMods) {
            if (mod == null || !mod.matches("[a-z][a-z0-9_]{0,63}")) return "Unavailable: invalid mod requirement";
            if (!mods.contains(mod)) missing.add("mod " + mod);
        }
        if (requiredCapabilities != null) for (String feature : requiredCapabilities) {
            if (feature == null || !feature.matches("[a-z0-9_.-]{1,128}")) return "Unavailable: invalid capability requirement";
            if (!features.contains(feature)) missing.add(feature);
        }
        return missing.isEmpty() ? "" : "Unavailable: requires " + String.join(", ", missing);
    }
}
