package net.xmilon.craftpy.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import net.neoforged.neoforge.network.PacketDistributor;
import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.keys.BindingStore;
import net.xmilon.craftpy.keys.KeyAction;
import net.xmilon.craftpy.network.ModuleKeysPayload;

/** One client registry drives Controls, the module editor and action dispatch. Client thread only. */
public final class ModuleKeys {
    private static BindingStore store;
    private static final Map<BindingStore.Id, Mapping> MAPPINGS = new HashMap<>();
    private static final Map<String, Long> REVISIONS = new HashMap<>();
    private static String remoteScope = "";
    private ModuleKeys() { }

    private static BindingStore store() {
        if (store == null) {
            try { store = new BindingStore(IdeHost.get().configDir().resolve("craftpy-module-keys.json")); }
            catch (IOException error) { throw new IllegalStateException(error.getMessage(), error); }
        }
        return store;
    }

    private static String serverScope() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.hasSingleplayerServer()) return "world:" + mc.getSingleplayerServer()
                .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();
        return "server:" + (mc.getCurrentServer() == null ? "unknown" : mc.getCurrentServer().ip.toLowerCase(java.util.Locale.ROOT));
    }

    public static void local(String owner, JsonArray descriptions) {
        try { store().replace("client", owner, KeyAction.parse(descriptions)); mirror(); }
        catch (IOException | RuntimeException error) { report(error); }
    }

    public static void forgetLocal(String owner) {
        if (store != null) { store.deactivate("client", owner); mirror(); }
    }

    public static void disconnect() {
        if (store != null) {
            store.deactivate("client", null);
            if (!remoteScope.isEmpty()) store.deactivate(remoteScope, null);
        }
        remoteScope = ""; REVISIONS.clear(); mirror();
    }

    public static void receive(String json) {
        try {
            JsonArray rows = JsonParser.parseString(json).getAsJsonArray();
            if (rows.size() > 128) throw new IllegalArgumentException("Too many key action owners");
            // Validate the complete packet before changing any active entry.
            Map<String, List<KeyAction>> descriptions = new HashMap<>();
            Map<String, Long> revisions = new HashMap<>();
            int count = 0;
            for (var raw : rows) {
                var row = raw.getAsJsonObject(); String owner = row.get("owner").getAsString();
                if (owner.length() > 160 || owner.isBlank() || descriptions.containsKey(owner)) throw new IllegalArgumentException("Invalid key owner");
                var actions = KeyAction.parse(row.getAsJsonArray("actions")); count += actions.size();
                if (count > 256) throw new IllegalArgumentException("Too many key actions");
                descriptions.put(owner, actions); revisions.put(owner, row.get("revision").getAsLong());
            }
            if (!remoteScope.isEmpty()) store().deactivate(remoteScope, null);
            remoteScope = serverScope(); store().deactivate(remoteScope, null);
            for (var row : descriptions.entrySet()) store().replace(remoteScope, row.getKey(), row.getValue());
            REVISIONS.clear(); REVISIONS.putAll(revisions); mirror();
        } catch (IOException | RuntimeException error) { report(error); }
    }

    public static List<BindingStore.Entry> entries() { return store().entries(); }

    public static void assign(BindingStore.Id id, String combo) throws IOException { store().assign(id, combo); mirror(); }

    public static List<String> conflicts(BindingStore.Id id, String combo) {
        String normalized = KeyCombo.normalize(combo);
        List<String> names = new ArrayList<>();
        for (var e : store().matching(normalized)) if (!e.id().equals(id)) names.add(e.action().name() + " (" + e.id().owner() + ")");
        if (!normalized.isEmpty() && IdeHost.get().inGame()) {
            var key = input(normalized);
            for (KeyMapping mapping : Minecraft.getInstance().options.keyMappings) {
                int required = switch (mapping.getKeyModifier()) { case CONTROL -> 2; case SHIFT -> 1; case ALT -> 4; default -> 0; };
                if (!(mapping instanceof Mapping) && mapping.getKey().equals(key) && (KeyCombo.modifiers(normalized) & required) == required)
                    names.add(net.minecraft.client.resources.language.I18n.get(mapping.getName()));
            }
        }
        return names;
    }

    public static void press(String combo) {
        if (store == null || Minecraft.getInstance().screen != null || Minecraft.getInstance().player == null) return;
        for (var e : store.matching(combo)) {
            if (e.id().scope().equals("client")) ClientPython.get().key(e.id().owner(), e.id().action());
            else if (e.id().scope().equals(remoteScope) && REVISIONS.containsKey(e.id().owner()))
                PacketDistributor.sendToServer(new ModuleKeysPayload.Press(e.id().owner(), e.id().action(), REVISIONS.get(e.id().owner())));
        }
    }

    /** Controls mutates the same KeyMapping instances. Import those edits before the next input event. */
    public static void tick() {
        if (store == null) return;
        for (var pair : MAPPINGS.entrySet()) {
            Mapping mapping = pair.getValue();
            String actual = combo(mapping);
            while (mapping.consumeClick()) { }
            if (!actual.equals(mapping.mirrored)) {
                try {
                    store.assign(pair.getKey(), actual); mapping.mirrored = actual;
                    mapping.entry = new BindingStore.Entry(mapping.entry.id(), mapping.entry.action(), actual, mapping.entry.active());
                }
                catch (IOException | RuntimeException error) { report(error); mapping.apply(mapping.entry); }
            }
        }
    }

    private static void mirror() {
        if (store == null || !IdeHost.get().inGame()) return;
        Minecraft mc = Minecraft.getInstance();
        List<KeyMapping> all = new ArrayList<>(java.util.Arrays.stream(mc.options.keyMappings).filter(k -> !(k instanceof Mapping)).toList());
        for (var entry : store.entries()) {
            Mapping mapping = MAPPINGS.computeIfAbsent(entry.id(), id -> new Mapping(entry));
            mapping.apply(entry);
            if (entry.active()) all.add(mapping);
        }
        mc.options.keyMappings = all.toArray(KeyMapping[]::new);
        KeyMapping.resetMapping();
    }

    private static InputConstants.Key input(String combo) {
        String base = combo.substring(combo.lastIndexOf('+') + 1);
        return base.startsWith("mouse") ? InputConstants.Type.MOUSE.getOrCreate(Integer.parseInt(base.substring(5)) - 1)
                : InputConstants.Type.KEYSYM.getOrCreate(KeyCombo.code(combo));
    }

    private static KeyModifier modifier(String combo) {
        return switch (KeyCombo.modifiers(combo)) { case 1 -> KeyModifier.SHIFT; case 2 -> KeyModifier.CONTROL; case 4 -> KeyModifier.ALT; default -> KeyModifier.NONE; };
    }

    private static String combo(KeyMapping mapping) {
        if (mapping.isUnbound()) return "";
        int mods = switch (mapping.getKeyModifier()) { case SHIFT -> 1; case CONTROL -> 2; case ALT -> 4; default -> 0; };
        return mapping.getKey().getType() == InputConstants.Type.MOUSE ? KeyCombo.mouse(mapping.getKey().getValue(), mods)
                : KeyCombo.name(mapping.getKey().getValue(), mods);
    }

    private static void report(Exception error) {
        CraftPyLog.LOGGER.error("Module keybindings: {}", error.getMessage());
        ClientProjectStore.getInstance().append("Module keybindings: " + error.getMessage());
    }

    private static final class Mapping extends KeyMapping {
        BindingStore.Entry entry;
        String mirrored = "";
        Mapping(BindingStore.Entry entry) {
            super("craftpy.module." + entry.id(), KeyConflictContext.IN_GAME, modifier(entry.action().combo()), input(entry.action().combo()), entry.action().category());
            this.entry = entry;
        }
        void apply(BindingStore.Entry entry) {
            this.entry = entry;
            // Controls supports one modifier. Full chords remain editable on the Module Keybinds page.
            String standard = entry.active() && Integer.bitCount(KeyCombo.modifiers(entry.assigned())) <= 1 ? entry.assigned() : "";
            setKeyModifierAndCode(modifier(standard), input(standard)); mirrored = combo(this);
        }
        @Override public String getName() {
            return entry == null ? super.getName() : entry.action().name() + " (" + entry.id().owner() + "/" + entry.id().action() + ")"
                    + (Integer.bitCount(KeyCombo.modifiers(entry.assigned())) > 1 ? " [" + entry.assigned() + "; Module Keybinds]" : "");
        }
        @Override public String getCategory() { return entry == null ? super.getCategory() : entry.action().category(); }
        @Override public InputConstants.Key getDefaultKey() { return entry == null ? super.getDefaultKey() : input(entry.action().combo()); }
        @Override public KeyModifier getDefaultKeyModifier() { return entry == null ? super.getDefaultKeyModifier() : modifier(entry.action().combo()); }
        @Override public boolean isDefault() { return entry == null ? super.isDefault() : entry.assigned().equals(entry.action().combo()); }
    }
}
