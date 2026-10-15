package net.xmilon.craftpy.client.assets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

/** Plays and stops a single preview sound event in the UI. Client thread only. */
public final class SoundPreview {
    private static SoundInstance current;

    private SoundPreview() {
    }

    /** False when nothing plays: unknown sound, or the standalone IDE (no sound engine, no registries). */
    public static boolean play(String soundId, float pitch, float volume) {
        if (!GameResources.inGame()) {
            return false;
        }
        SoundEvent event = resolve(soundId);
        if (event == null) {
            return false;
        }
        stop();
        current = SimpleSoundInstance.forUI(event, pitch, volume);
        Minecraft.getInstance().getSoundManager().play(current);
        return true;
    }

    public static void stop() {
        if (current != null) {
            if (GameResources.inGame()) {
                Minecraft.getInstance().getSoundManager().stop(current);
            }
            current = null;
        }
    }

    public static boolean isPlaying() {
        return current != null && GameResources.inGame() && Minecraft.getInstance().getSoundManager().isActive(current);
    }

    private static SoundEvent resolve(String soundId) {
        ResourceLocation id = resolveId(soundId);
        if (id == null) {
            return null;
        }
        SoundEvent registered = BuiltInRegistries.SOUND_EVENT.get(id);
        // events that only a sounds.json defines (a project's own) are not in the registry but play by name
        return registered != null ? registered : SoundEvent.createVariableRangeEvent(id);
    }

    private static ResourceLocation resolveId(String soundId) {
        if (soundId == null || soundId.isBlank()) {
            return null;
        }
        try {
            return ResourceLocation.parse(soundId);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
