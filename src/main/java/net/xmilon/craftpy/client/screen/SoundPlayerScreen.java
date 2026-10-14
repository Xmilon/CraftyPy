package net.xmilon.craftpy.client.screen;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.xmilon.craftpy.client.ClientProjectStore;
import net.xmilon.craftpy.client.assets.AssetPreview;
import net.xmilon.craftpy.client.assets.SoundPreview;
import net.xmilon.craftpy.util.CraftPyExecutors;

/**
 * Viewer/player for a single sound event. Plays the event, lets the user adjust
 * volume and pitch and toggle looping, and can copy the resolved .ogg into the
 * current project's {@code assets/sounds/} folder so it can be overridden.
 */
public class SoundPlayerScreen extends Screen {
    private final Screen parent;
    private final String projectId;
    private final String soundId;

    private float volume = 1.0f;
    private float pitch = 1.0f;
    private boolean looping;
    private String status = "";
    private ResourceLocation resolved;
    /** Peak amplitude (0..1) per waveform column, decoded off-thread; null while loading. */
    private volatile float[] peaks;
    private volatile String info = "";

    public SoundPlayerScreen(Screen parent, String projectId, String soundId) {
        super(Component.literal("Sound player"));
        this.parent = parent;
        this.projectId = projectId;
        this.soundId = soundId;
        this.resolved = AssetPreview.soundFile(soundId);
    }

    @Override
    protected void init() {
        int y = height - 26;
        int bx = 12;
        addRenderableWidget(Button.builder(Component.literal("Play"), b -> play(false)).bounds(bx, y, 60, 18).build());
        bx += 64;
        addRenderableWidget(Button.builder(Component.literal("Loop"), b -> play(true)).bounds(bx, y, 60, 18).build());
        bx += 64;
        addRenderableWidget(Button.builder(Component.literal("Stop"), b -> {
            SoundPreview.stop();
            status = "Stopped";
        }).bounds(bx, y, 60, 18).build());
        bx += 72;
        addRenderableWidget(Button.builder(Component.literal("Vol -"), b -> adjustVolume(-0.1f))
                .bounds(bx, y, 54, 18).build());
        bx += 58;
        addRenderableWidget(Button.builder(Component.literal("Vol +"), b -> adjustVolume(0.1f))
                .bounds(bx, y, 54, 18).build());
        bx += 58;
        addRenderableWidget(Button.builder(Component.literal("Pitch -"), b -> adjustPitch(-0.1f))
                .bounds(bx, y, 62, 18).build());
        bx += 66;
        addRenderableWidget(Button.builder(Component.literal("Pitch +"), b -> adjustPitch(0.1f))
                .bounds(bx, y, 62, 18).build());

        addRenderableWidget(Button.builder(Component.literal("Copy as code"), b -> copyAsCode())
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
                        "Copies a sound(...) declaration with this volume and pitch")))
                .bounds(width - 330, y, 100, 18).build());
        if (projectId != null) {
            addRenderableWidget(Button.builder(Component.literal("Copy to project"), b -> save())
                    .bounds(width - 226, y, 120, 18).build());
        }
        if (peaks == null && resolved != null) {
            final ResourceLocation file = resolved;
            CraftPyExecutors.runAsync(() -> decode(file), CraftPyExecutors.ui());
        }
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(width - 102, y, 90, 18).build());
    }

    private void adjustVolume(float delta) {
        volume = Math.max(0f, Math.min(1f, volume + delta));
        SoundPreview.setPitchVolume(pitch, volume);
        status = "Volume " + pct(volume);
    }

    private void adjustPitch(float delta) {
        pitch = Math.max(0.5f, Math.min(2f, pitch + delta));
        SoundPreview.setPitchVolume(pitch, volume);
        status = "Pitch " + String.format(Locale.ROOT, "%.2f", pitch);
    }

    private void play(boolean loop) {
        SoundPreview.setPitchVolume(pitch, volume);
        boolean ok = loop ? SoundPreview.playLooping(soundId, pitch, volume) : SoundPreview.play(soundId, pitch, volume);
        looping = ok && loop;
        status = ok ? (loop ? "Looping" : "Playing") : "Could not play this sound event";
    }

    private void save() {
        if (resolved == null) {
            status = "Nothing to copy: no physical .ogg resolved for this event";
            return;
        }
        String rel = resolved.getPath().startsWith("sounds/")
                ? resolved.getPath().substring("sounds/".length())
                : resolved.getPath();
        String path = "assets/sounds/" + rel;
        final ResourceLocation file = resolved;
        status = "Copying " + path + " ...";
        CraftPyExecutors.supplyAsync(() -> AssetPreview.readResource(file), CraftPyExecutors.ui()).thenAccept(bytes -> Minecraft.getInstance()
                .execute(() -> {
                    if (bytes == null || bytes.length == 0) {
                        status = "Copy failed: could not read " + file;
                        return;
                    }
                    ClientProjectStore.getInstance().uploadAsset(projectId, path, bytes);
                    status = "Copied " + path + " (" + bytes.length + " bytes)";
                }));
    }

    /**
     * Sounds cannot be re-encoded to .ogg here (Minecraft ships a decoder only), so "editing" a sound
     * means declaring a sound event with its own volume/pitch - this copies that declaration.
     */
    private void copyAsCode() {
        String name = soundId;
        if (resolved != null) {
            String path = resolved.getPath();
            if (path.startsWith("sounds/")) {
                path = path.substring("sounds/".length());
            }
            if (path.endsWith(".ogg")) {
                path = path.substring(0, path.length() - 4);
            }
            name = resolved.getNamespace() + ":" + path;
        }
        String event = soundId.contains(":") ? soundId.substring(soundId.indexOf(':') + 1) : soundId;
        String code = String.format(Locale.ROOT,
                "sound(\"my_%s\", sounds=[{\"name\": \"%s\", \"volume\": %.2f, \"pitch\": %.2f}])",
                event.replace('.', '_'), name, volume, pitch);
        Minecraft.getInstance().keyboardHandler.setClipboard(code);
        status = "Copied: " + code;
    }

    /** Decodes the .ogg with stb_vorbis (bundled with Minecraft) into per-column peaks. */
    private void decode(ResourceLocation file) {
        byte[] bytes = AssetPreview.readResource(file);
        if (bytes == null || bytes.length == 0) {
            info = "Could not read " + file;
            peaks = new float[0];
            return;
        }
        java.nio.ByteBuffer data = org.lwjgl.system.MemoryUtil.memAlloc(bytes.length);
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            data.put(bytes).flip();
            java.nio.IntBuffer channels = stack.mallocInt(1);
            java.nio.IntBuffer rate = stack.mallocInt(1);
            java.nio.ShortBuffer pcm = org.lwjgl.stb.STBVorbis.stb_vorbis_decode_memory(data, channels, rate);
            if (pcm == null) {
                info = "Not a readable Ogg Vorbis file";
                peaks = new float[0];
                return;
            }
            try {
                int ch = Math.max(1, channels.get(0));
                int frames = pcm.remaining() / ch;
                int columns = 400;
                float[] out = new float[columns];
                for (int frame = 0; frame < frames; frame++) {
                    int column = (int) ((long) frame * columns / Math.max(1, frames));
                    for (int c = 0; c < ch; c++) {
                        float sample = Math.abs(pcm.get(frame * ch + c)) / 32768f;
                        if (sample > out[column]) {
                            out[column] = sample;
                        }
                    }
                }
                info = String.format(Locale.ROOT, "%.2f s  |  %d Hz  |  %s  |  %d KB", frames / (float) rate.get(0),
                        rate.get(0), ch == 1 ? "mono" : ch + " channels", bytes.length / 1024);
                peaks = out;
            } finally {
                org.lwjgl.system.libc.LibCStdlib.free(pcm);
            }
        } catch (Throwable t) {
            info = "Could not decode: " + t.getMessage();
            peaks = new float[0];
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(data);
        }
    }

    private static String pct(float value) {
        return Math.round(value * 100) + "%";
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawString(font, "Sound player", 12, 12, 0x4FC3F7, false);
        graphics.drawString(font, soundId, 12, 30, 0xFFFFFF, false);

        AssetPreview.drawSpeaker(graphics, 12, 48, 48);
        graphics.drawString(font, "Event: " + soundId, 72, 50, 0xD7E1EA, false);
        graphics.drawString(font, "File: " + (resolved == null ? "unresolved" : resolved.toString()), 72, 64,
                resolved == null ? 0xE57373 : 0xD7E1EA, false);
        graphics.drawString(font, "Volume: " + pct(volume), 72, 78, 0xD7E1EA, false);
        graphics.drawString(font, "Pitch: " + String.format(Locale.ROOT, "%.2f", pitch), 72, 92, 0xD7E1EA, false);
        graphics.drawString(font, "State: " + (SoundPreview.isPlaying() ? (looping ? "looping" : "playing") : "stopped"),
                72, 106, 0xD7E1EA, false);

        // Waveform
        int waveX = 12;
        int waveY = 146;
        int waveW = width - 24;
        int waveH = Math.max(40, Math.min(120, height - waveY - 40));
        graphics.fill(waveX, waveY, waveX + waveW, waveY + waveH, 0xFF1E1F22);
        int mid = waveY + waveH / 2;
        graphics.fill(waveX, mid, waveX + waveW, mid + 1, 0xFF34373D);
        float[] wave = peaks;
        if (wave == null) {
            graphics.drawString(font, resolved == null ? "No .ogg file resolved for this event." : "Decoding...",
                    waveX + 6, waveY + 6, 0x8A97A4, false);
        } else if (wave.length > 0) {
            for (int x = 0; x < waveW; x++) {
                float peak = wave[Math.min(wave.length - 1, x * wave.length / waveW)] * volume;
                int half = Math.max(1, Math.round(Math.min(1f, peak) * (waveH / 2f - 2)));
                graphics.fill(waveX + x, mid - half, waveX + x + 1, mid + half, 0xFF4FC3F7);
            }
        }
        graphics.drawString(font, info, waveX, waveY + waveH + 4, 0x8A97A4, false);

        if (projectId == null) {
            graphics.drawString(font, "Open a project to copy this sound into it.", 12, 118, 0xFFB86C, false);
        }
        if (!status.isEmpty()) {
            graphics.drawString(font, status, 12, 128, 0xFFB86C, false);
        }
    }

    @Override
    public void tick() {
        super.tick();
        SoundPreview.tick();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        SoundPreview.stop();
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }
}
