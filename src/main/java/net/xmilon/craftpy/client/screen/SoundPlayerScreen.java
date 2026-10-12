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

        if (projectId != null) {
            addRenderableWidget(Button.builder(Component.literal("Copy to project"), b -> save())
                    .bounds(width - 226, y, 120, 18).build());
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
