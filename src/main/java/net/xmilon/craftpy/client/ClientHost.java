package net.xmilon.craftpy.client;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.xmilon.craftpy.CraftPyLog;

/**
 * Answers the calls of client-side Python. This is the whole surface of the client worker: it draws, listens to keys,
 * reads what the client already knows, shows things only this player sees, and opens local windows. Every other call
 * (changing the world, running commands, anything of the server) is refused by name, so a client module cannot reach
 * past the player's own screen. Runs on the worker's host-call thread; anything that touches the game hops to the
 * client thread first.
 */
public final class ClientHost {

    private ClientHost() {
    }

    public static void reset() {
        ClientHud.clearAll();
        IdeHost.get().execute(ModuleKeys::disconnect);
    }

    /** An owner's worker stopped: take its HUD and keys away. */
    static void forget(String owner) {
        ClientHud.clear(owner);
        IdeHost.get().execute(() -> ModuleKeys.forgetLocal(owner));
    }

    /**
     * A reverse call from the client worker of {@code owner}. Each owner has its own worker, so the host knows who calls:
     * the owner the script may have put in {@code p} is replaced by that one before anything reads it.
     */
    public static JsonObject handle(String owner, String method, JsonObject p) {
        p.addProperty("owner", owner);
        JsonObject access = net.xmilon.craftpy.security.AccessBridge.handle(owner, method, p,
                event -> ClientPython.get().event("http_response", event));
        if (access != null) {
            return access;
        }
        try {
            return switch (method) {
                case "hud.set" -> ClientHud.set(p) ? ok() : fail("hud: that element is not valid, or the limit of elements is reached");
                case "hud.remove" -> {
                    ClientHud.remove(string(p, "owner"), string(p, "id"));
                    yield ok();
                }
                case "hud.clear" -> {
                    ClientHud.clear(string(p, "owner"));
                    yield ok();
                }
                case "client.player" -> onClient(ClientHost::playerData);
                case "client.looking_at" -> onClient(ClientHost::lookingAt);
                case "client.fps" -> onClient(() -> {
                    JsonObject out = ok();
                    out.addProperty("fps", Minecraft.getInstance().getFps());
                    return out;
                });
                case "client.chat", "player.send_message" -> onClient(() -> chat(string(p, "text", string(p, "message"))));
                case "client.sound", "player.sound" -> onClient(() -> sound(p));
                case "client.toast" -> onClient(() -> toast(p));
                case "player.actionbar" -> onClient(() -> overlay(string(p, "message", string(p, "text"))));
                case "player.title" -> onClient(() -> title(p));
                case "ui.open" -> onClient(() -> window(p, true));
                case "ui.close" -> onClient(() -> window(p, false));
                case "storage.get" -> ClientStorage.get(storageKey(p));
                case "storage.set" -> ClientStorage.set(storageKey(p), p.get("value"));
                case "storage.clear" -> ClientStorage.clear(string(p, "key").isBlank() ? null : storageKey(p),
                        owner + "/" + string(p, "scope", "world") + "/");
                case "media.files" -> mediaFiles(p);
                case "media.play" -> mediaPlay(p);
                case "media.play_sound" -> onClient(() -> {
                    var id = net.minecraft.resources.ResourceLocation.tryParse(string(p, "sound"));
                    if (id == null || Minecraft.getInstance().getSoundManager().getSoundEvent(id) == null) {
                        return fail("Unknown sound event: " + string(p, "sound"));
                    }
                    net.xmilon.craftpy.client.media.MediaPlayer.get().playSound(id, (float) number(p, "volume", 1));
                    return ok();
                });
                case "media.sounds" -> onClient(ClientHost::musicSounds);
                case "media.pause" -> media(player -> player.pause());
                case "media.resume" -> media(player -> player.resume());
                case "media.stop" -> media(player -> player.stop());
                case "media.seek" -> media(player -> player.seek(number(p, "seconds", 0)));
                case "media.volume" -> media(player -> player.volume((float) number(p, "volume", 1)));
                case "media.state" -> {
                    JsonObject out = ok();
                    out.add("state", net.xmilon.craftpy.client.media.MediaPlayer.get().state());
                    yield out;
                }
                case "server.log" -> {
                    CraftPyLog.LOGGER.info("[client module] {}", string(p, "message"));
                    yield ok();
                }
                default -> fail("'" + method + "' is not available in client-side code: it would change the world or reach"
                        + " the server. Client code draws, listens to keys and reads what the client knows.");
            };
        } catch (RuntimeException e) {
            return fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // -- media player ------------------------------------------------------------------------------------------------

    /** The folder of pyforge/shared the owner declared and had approved, or an exception with the reason. */
    private static java.nio.file.Path sharedFolder(JsonObject p) throws java.io.IOException {
        String folder = string(p, "folder", "music");
        if (!net.xmilon.craftpy.client.media.MediaLibrary.validFolder(folder)) {
            throw new java.io.IOException("Folder names are plain words like 'music'");
        }
        String owner = string(p, "owner");
        if (!net.xmilon.craftpy.security.PermissionStore.sharedAllowed(owner, folder)) {
            throw new java.io.IOException("Reading pyforge/shared/" + folder + " needs \"permissions\": {\"shared\": [\""
                    + folder + "\"]} in project.json / module.json, approved on the Modules page");
        }
        java.nio.file.Path dir = net.xmilon.craftpy.client.media.MediaLibrary.folder(folder);
        java.nio.file.Files.createDirectories(dir);
        return dir;
    }

    private static JsonObject mediaFiles(JsonObject p) {
        try {
            JsonObject out = ok();
            JsonArray names = new JsonArray();
            net.xmilon.craftpy.client.media.MediaLibrary.list(sharedFolder(p)).forEach(names::add);
            out.add("files", names);
            return out;
        } catch (java.io.IOException e) {
            return fail(e.getMessage());
        }
    }

    private static JsonObject mediaPlay(JsonObject p) {
        java.nio.file.Path file;
        try {
            file = net.xmilon.craftpy.client.media.MediaLibrary.resolve(sharedFolder(p), string(p, "file"));
        } catch (java.io.IOException e) {
            return fail(e.getMessage());
        }
        return onClient(() -> {
            net.xmilon.craftpy.client.media.MediaPlayer.get().playFile(string(p, "file"), file, number(p, "seconds", 0),
                    (float) number(p, "volume", 1), p.has("share") && p.get("share").getAsBoolean());
            return ok();
        });
    }

    private static JsonObject media(java.util.function.Consumer<net.xmilon.craftpy.client.media.MediaPlayer> action) {
        return onClient(() -> {
            action.accept(net.xmilon.craftpy.client.media.MediaPlayer.get());
            return ok();
        });
    }

    /** Music of the game, resource packs and mods: sound events whose id says music or record (music discs). */
    private static JsonObject musicSounds() {
        JsonObject out = ok();
        JsonArray names = new JsonArray();
        Minecraft.getInstance().getSoundManager().getAvailableSounds().stream()
                .map(Object::toString)
                .filter(id -> id.contains("music") || id.contains("record"))
                .filter(id -> !id.equals("craftpy:media"))
                .sorted()
                .limit(1000)
                .forEach(names::add);
        out.add("sounds", names);
        return out;
    }

    private static double number(JsonObject p, String key, double fallback) {
        try {
            return p.has(key) && !p.get(key).isJsonNull() ? p.get(key).getAsDouble() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** Kept per owner (handle sets {@code owner} from the host): one module cannot read or wipe another's values. */
    private static String storageKey(JsonObject p) {
        return string(p, "owner") + "/" + string(p, "scope", "world") + "/" + string(p, "key");
    }

    // -- things that need the client thread ---------------------------------------------------------------------------

    private static JsonObject onClient(Supplier<JsonObject> work) {
        if (!net.xmilon.craftpy.client.IdeHost.get().inGame()) {
            return fail("This needs the game: open a world");
        }
        try {
            return Minecraft.getInstance().submit(work).get(3, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return fail("The game did not answer in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail("Interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            return fail(String.valueOf(e.getCause()));
        }
    }

    private static JsonObject playerData() {
        var player = Minecraft.getInstance().player;
        JsonObject out = ok();
        if (player == null) {
            out.add("player", new JsonObject());
            return out;
        }
        JsonObject data = new JsonObject();
        data.addProperty("name", player.getGameProfile().getName());
        data.addProperty("uuid", player.getUUID().toString());
        data.addProperty("health", player.getHealth());
        data.addProperty("max_health", player.getMaxHealth());
        data.addProperty("food", player.getFoodData().getFoodLevel());
        data.addProperty("level", player.experienceLevel);
        data.addProperty("x", player.getX());
        data.addProperty("y", player.getY());
        data.addProperty("z", player.getZ());
        data.addProperty("dimension", player.level().dimension().location().toString());
        data.addProperty("gamemode", Minecraft.getInstance().gameMode == null ? ""
                : Minecraft.getInstance().gameMode.getPlayerMode().getName());
        var held = player.getMainHandItem();
        data.addProperty("held_item", BuiltInRegistries.ITEM.getKey(held.getItem()).toString());
        data.addProperty("held_count", held.getCount());
        data.addProperty("on_ground", player.onGround());
        out.add("player", data);
        return out;
    }

    private static JsonObject lookingAt() {
        var minecraft = Minecraft.getInstance();
        JsonObject out = ok();
        HitResult hit = minecraft.hitResult;
        if (minecraft.level != null && hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
            JsonObject target = new JsonObject();
            var state = minecraft.level.getBlockState(block.getBlockPos());
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if (state.getBlock() instanceof net.xmilon.craftpy.block.SlottedBlock slotted) {
                // a CraftPy block: its project id (ruby_rush:ruby_ore), not the slot behind it
                String[] parts = net.xmilon.craftpy.registry.SlotRegistry.holderForBlock(slotted.slotIndex()).split(":", 3);
                if (parts.length == 3) {
                    id = parts[1] + ":" + parts[2];
                }
            }
            target.addProperty("block", id);
            JsonArray pos = new JsonArray();
            pos.add(block.getBlockPos().getX());
            pos.add(block.getBlockPos().getY());
            pos.add(block.getBlockPos().getZ());
            target.add("pos", pos);
            out.add("target", target);
        }
        return out;
    }

    private static JsonObject chat(String text) {
        Minecraft.getInstance().gui.getChat().addMessage(Component.literal(text));
        return ok();
    }

    private static JsonObject overlay(String text) {
        Minecraft.getInstance().gui.setOverlayMessage(Component.literal(text), false);
        return ok();
    }

    private static JsonObject title(JsonObject p) {
        var gui = Minecraft.getInstance().gui;
        gui.setTitle(Component.literal(string(p, "title", string(p, "text"))));
        if (p.has("subtitle") && p.get("subtitle").isJsonPrimitive()) {
            gui.setSubtitle(Component.literal(p.get("subtitle").getAsString()));
        }
        return ok();
    }

    private static JsonObject sound(JsonObject p) {
        ResourceLocation id = ResourceLocation.tryParse(string(p, "sound"));
        var event = id == null ? java.util.Optional.<net.minecraft.sounds.SoundEvent>empty()
                : BuiltInRegistries.SOUND_EVENT.getOptional(id);
        if (event.isEmpty()) {
            return fail("Unknown sound: " + string(p, "sound"));
        }
        float volume = p.has("volume") ? (float) Math.max(0, Math.min(4, p.get("volume").getAsDouble())) : 1f;
        float pitch = p.has("pitch") ? (float) Math.max(0.5, Math.min(2, p.get("pitch").getAsDouble())) : 1f;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(event.get(), pitch, volume));
        return ok();
    }

    private static JsonObject toast(JsonObject p) {
        var minecraft = Minecraft.getInstance();
        SystemToast.add(minecraft.getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.literal(string(p, "title")), Component.literal(string(p, "message")));
        return ok();
    }

    /** Opens or closes a window drawn by client-side Python; its clicks come back to the client worker. */
    private static JsonObject window(JsonObject p, boolean open) {
        if (open) {
            JsonElement tree = p.get("window");
            if (tree == null || !tree.isJsonObject()) {
                return fail("ui.open: no window");
            }
            JsonObject copy = JsonParser.parseString(tree.toString()).getAsJsonObject();
            copy.addProperty("local", true);
            ClientPython.get().noteWindow(string(p, "owner"), string(copy, "id"));
            net.xmilon.craftpy.client.mui.WindowFragment.apply("open", copy.toString());
        } else {
            net.xmilon.craftpy.client.mui.WindowFragment.apply("close", string(p, "id"));
        }
        return ok();
    }

    // -- results ------------------------------------------------------------------------------------------------------

    static JsonObject ok() {
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        return out;
    }

    static JsonObject fail(String message) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", false);
        out.addProperty("error", message);
        return out;
    }

    private static String string(JsonObject p, String key) {
        return string(p, key, "");
    }

    private static String string(JsonObject p, String key, String fallback) {
        JsonElement value = p.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }
}
