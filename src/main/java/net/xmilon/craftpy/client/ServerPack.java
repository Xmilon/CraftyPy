package net.xmilon.craftpy.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.ChatFormatting;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.network.ServerModulesPayload;
import net.xmilon.craftpy.project.ProjectManager;
import net.xmilon.craftpy.project.PyForgePaths;
import net.xmilon.craftpy.server.ServerPackZip;
import net.xmilon.craftpy.util.Json;

/**
 * Player side of joining a server from another computer: the warning about the server's modules that ask for
 * permissions ({@link net.xmilon.craftpy.client.mui.ServerModulesFragment}), and the server's resource pack, kept per
 * version in {@code pyforge/cache/serverpacks/} and unpacked for the game while you play there.
 * <p>
 * The pack comes from someone else's computer: only {@code pack.mcmeta} and files under {@code assets/} with the
 * resource types below are unpacked, never outside the pack folder, and never more than {@link ServerPackZip#MAX_BYTES}.
 */
public final class ServerPack {
    private static final Set<String> TYPES = Set.of("png", "json", "mcmeta", "ogg", "lang", "txt");

    /** The last info of the server you are on (modules that ask for permissions), for the screen and the command. */
    private static JsonObject lastInfo;
    private static IPayloadContext lastContext;
    private static String installedSha;
    private static ByteArrayOutputStream incoming;
    private static String incomingSha;

    private ServerPack() {
    }

    /** The unpacked pack of the server you are on; it exists only while you are on one. */
    /** True while the pack of the server you are on is unpacked and in use. */
    public static boolean active() {
        return installedSha != null && Files.isDirectory(root());
    }

    public static Path root() {
        return PyForgePaths.build().resolve("serverpack").resolve("craftpy-server");
    }

    // -- from the server (client thread) ------------------------------------------------------------------------------

    public static void receive(ServerModulesPayload payload, IPayloadContext context) {
        lastContext = context;
        switch (payload.op()) {
            case "info" -> onInfo(parse(payload.json()), context.protocol() == ConnectionProtocol.CONFIGURATION);
            case "chunk" -> onChunk(parse(payload.json()), payload.data());
            default -> {
            }
        }
    }

    private static void onInfo(JsonObject info, boolean joining) {
        lastInfo = info;
        if (remembered(serverKey(), listHash(info))) {
            for (JsonObject module : modules(info)) rememberModule(module, true);
            var legacy = known(); legacy.remove(serverKey());
            try { Json.write(knownFile(), legacy); } catch (IOException error) { CraftPyLog.LOGGER.warn("Could not migrate remembered server", error); }
        }
        boolean warn = modules(info).stream().anyMatch(m -> !rememberedModule(m));
        if (joining && warn) {
            Minecraft.getInstance().setScreen(net.xmilon.craftpy.client.mui.ServerModulesFragment.screen(info));
            return; // the screen calls continueJoin or leave
        }
        if (!joining && warn) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.literal("[CraftPy] This server now runs modules that "
                                + "can reach the web, files or commands. ")
                        .withStyle(ChatFormatting.GOLD)
                        .append(Component.literal("[Show them]").withStyle(style -> style.withColor(ChatFormatting.AQUA)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/craftpy_client server_modules"))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                        Component.literal("Their names, what they can do and their READMEs"))))), false);
            }
        }
        fetchPack();
    }

    /** Continue was pressed on the screen: remember the choice when asked, then fetch the pack. */
    public static void continueJoin(boolean remember) {
        if (remember && lastInfo != null) {
            for (JsonObject module : modules(lastInfo)) rememberModule(module, true);
        }
        fetchPack();
    }

    /** Leave was pressed: the server disconnects us before anything is sent. */
    public static void leave() {
        reply("reply", "{\"choice\":\"leave\"}");
    }

    /** Opens the screen again with the last info ({@code /craftpy_client server_modules}); false when there is none. */
    public static boolean showLast() {
        if (lastInfo == null || modules(lastInfo).isEmpty()) {
            return false;
        }
        Minecraft.getInstance().setScreen(net.xmilon.craftpy.client.mui.ServerModulesFragment.screen(lastInfo));
        return true;
    }

    /** True while the join screen is waiting for an answer (Continue then finishes joining). */
    public static boolean joining() {
        return lastContext != null && lastContext.protocol() == ConnectionProtocol.CONFIGURATION;
    }

    private static void fetchPack() {
        JsonObject pack = lastInfo != null && lastInfo.has("pack") ? lastInfo.getAsJsonObject("pack") : null;
        String sha = pack == null ? null : str(pack, "sha256", null);
        if (sha == null || !sha.matches("[0-9a-f]{64}")) {
            reply("reply", "{\"choice\":\"have\"}");
            return;
        }
        if (sha.equals(installedSha) && Files.isDirectory(root())) {
            reply("reply", "{\"choice\":\"have\"}");
            return;
        }
        Path cached = cacheOf(sha);
        if (Files.isRegularFile(cached)) {
            try {
                byte[] zip = Files.readAllBytes(cached);
                if (sha.equals(ServerPackZip.sha256(zip))) {
                    install(zip, sha, "have");
                    return;
                }
            } catch (IOException e) {
                CraftPyLog.LOGGER.warn("Kept server pack {} could not be read; fetching it again", sha, e);
            }
        }
        incoming = new ByteArrayOutputStream();
        incomingSha = sha;
        reply("reply", "{\"choice\":\"need\"}");
    }

    private static void onChunk(JsonObject meta, byte[] data) {
        if (incoming == null || !String.valueOf(incomingSha).equals(str(meta, "sha256", ""))) {
            return;
        }
        if (incoming.size() + data.length > ServerPackZip.MAX_BYTES) {
            CraftPyLog.LOGGER.warn("Server pack is larger than {} bytes; not used", ServerPackZip.MAX_BYTES);
            incoming = null;
            reply("installed", "{}");
            return;
        }
        incoming.writeBytes(data);
        int index = meta.has("index") ? meta.get("index").getAsInt() : 0;
        int total = meta.has("total") ? meta.get("total").getAsInt() : 1;
        if (index + 1 < total) {
            return;
        }
        byte[] zip = incoming.toByteArray();
        String sha = incomingSha;
        incoming = null;
        if (!sha.equals(ServerPackZip.sha256(zip))) {
            CraftPyLog.LOGGER.warn("Server pack arrived damaged (hash differs); not used");
            reply("installed", "{}");
            return;
        }
        try {
            Path cached = cacheOf(sha);
            Files.createDirectories(cached.getParent());
            Files.write(cached, zip);
        } catch (IOException e) {
            CraftPyLog.LOGGER.warn("Could not keep the server pack", e);
        }
        install(zip, sha, "installed");
    }

    /** Unpacks the pack, reloads the resources, then answers the server ({@code have} or {@code installed}). */
    private static void install(byte[] zip, String sha, String answer) {
        try {
            extract(zip, root());
            installedSha = sha;
        } catch (IOException e) {
            CraftPyLog.LOGGER.warn("Server pack refused: {}", e.getMessage());
            quietDelete(root());
        }
        String json = "have".equals(answer) ? "{\"choice\":\"have\"}" : "{}";
        String op = "have".equals(answer) ? "reply" : "installed";
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.reloadResourcePacks().whenComplete((ignored, error) -> minecraft.execute(() -> {
            ClientPackSync.announceContent(minecraft);
            reply(op, json);
        }));
    }

    /** Leaving the server: its pack goes away, so the next world or server starts clean. */
    public static void forget() {
        lastInfo = null;
        lastContext = null;
        incoming = null;
        installedSha = null;
        if (Files.exists(root())) {
            quietDelete(root());
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.execute(minecraft::reloadResourcePacks);
        }
    }

    private static void reply(String op, String json) {
        if (lastContext != null) {
            lastContext.reply(new ServerModulesPayload(op, json));
        }
    }

    // -- the pack on disk ----------------------------------------------------------------------------------------------

    private static Path cacheOf(String sha) {
        return PyForgePaths.cache().resolve("serverpacks").resolve(sha + ".zip");
    }

    /**
     * Unpacks {@code zip} into {@code target} (replacing it). Throws, leaving nothing behind, when an entry is outside
     * {@code assets/}, has a file type a resource pack does not need, or the pack is too large.
     */
    public static void extract(byte[] zip, Path target) throws IOException {
        Path staging = target.resolveSibling(target.getFileName() + ".new");
        ProjectManager.deleteRecursively(staging);
        Files.createDirectories(staging);
        long written = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) {
                    continue;
                }
                Path out = checked(staging, name);
                Files.createDirectories(out.getParent());
                try (OutputStream file = Files.newOutputStream(out)) {
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        written += n;
                        if (written > ServerPackZip.MAX_BYTES) {
                            throw new IOException("the pack is larger than " + ServerPackZip.MAX_BYTES + " bytes");
                        }
                        file.write(buffer, 0, n);
                    }
                }
            }
        } catch (IOException e) {
            ProjectManager.deleteRecursively(staging);
            throw e;
        }
        ProjectManager.deleteRecursively(target);
        Files.createDirectories(target.getParent());
        Files.move(staging, target);
    }

    /** Where entry {@code name} goes under {@code root}; throws for anything a resource pack must not contain. */
    static Path checked(Path root, String name) throws IOException {
        if (name.equals("pack.mcmeta")) {
            return root.resolve(name);
        }
        if (!name.startsWith("assets/") || name.contains("\\") || name.contains(":") || name.contains("//")) {
            throw new IOException("not a resource: " + name);
        }
        for (String part : name.split("/")) {
            if (part.equals("..") || part.equals(".")) {
                throw new IOException("not a resource: " + name);
            }
        }
        int dot = name.lastIndexOf('.');
        String type = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw new IOException("file type not allowed in a resource pack: " + name);
        }
        Path out = root.resolve(name).normalize();
        if (!out.startsWith(root.normalize())) {
            throw new IOException("not a resource: " + name);
        }
        return out;
    }

    // -- "don't show again for this server" -----------------------------------------------------------------------------

    private static Path choicesFile;
    private static ModuleAcknowledgments savedChoices;
    private static ModuleAcknowledgments choices() {
        Path file = PyForgePaths.system().resolve("server_module_choices.json");
        if (!file.equals(choicesFile)) { choicesFile = file; savedChoices = new ModuleAcknowledgments(file); }
        return savedChoices;
    }
    public static boolean rememberedModule(JsonObject module) {
        return choices().remembered(serverKey(), str(module, "id", ""), str(module, "sha256", ""));
    }
    public static void rememberModule(JsonObject module, boolean remember) {
        choices().set(serverKey(), str(module, "id", ""), str(module, "sha256", ""), remember);
    }

    private static Path knownFile() {
        return PyForgePaths.system().resolve("known_servers.json");
    }

    private static String serverKey() {
        var server = Minecraft.getInstance().getCurrentServer();
        return server == null ? "" : server.ip.toLowerCase(Locale.ROOT);
    }

    /** The modules that ask for permissions, as sent by the server. */
    public static List<JsonObject> modules(JsonObject info) {
        List<JsonObject> out = new ArrayList<>();
        if (info != null && info.has("modules") && info.get("modules").isJsonArray()) {
            for (JsonElement element : info.getAsJsonArray("modules")) {
                if (element.isJsonObject()) {
                    out.add(element.getAsJsonObject());
                }
            }
        }
        return out;
    }

    /** Hash of the sorted module ids and file hashes: a new module or a new version of one shows the screen again. */
    static String listHash(JsonObject info) {
        List<String> lines = new ArrayList<>();
        for (JsonObject module : modules(info)) {
            lines.add(str(module, "id", "") + ":" + str(module, "sha256", ""));
        }
        lines.sort(null);
        return ServerPackZip.sha256(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    static boolean remembered(String server, String hash) {
        if (server.isEmpty()) {
            return false;
        }
        JsonObject known = known();
        return known.has(server) && hash.equals(known.get(server).getAsString());
    }

    static void remember(String server, String hash) {
        if (server.isEmpty()) {
            return;
        }
        JsonObject known = known();
        known.addProperty(server, hash);
        try {
            Json.write(knownFile(), known);
        } catch (IOException e) {
            CraftPyLog.LOGGER.warn("Could not save known_servers.json", e);
        }
    }

    private static JsonObject known() {
        try {
            if (Files.isRegularFile(knownFile())) {
                return JsonParser.parseString(Files.readString(knownFile())).getAsJsonObject();
            }
        } catch (IOException | RuntimeException e) {
            CraftPyLog.LOGGER.warn("known_servers.json could not be read", e);
        }
        return new JsonObject();
    }

    private static JsonObject parse(String json) {
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    private static void quietDelete(Path path) {
        try {
            ProjectManager.deleteRecursively(path);
        } catch (IOException e) {
            CraftPyLog.LOGGER.warn("Could not delete {}", path, e);
        }
    }

    private static String str(JsonObject object, String key, String fallback) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }
}
