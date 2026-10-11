package net.xmilon.craftpy.client.assets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

/**
 * Plays and stops a single preview sound event in the UI. Loop is emulated from
 * the owning screen's tick by replaying once the previous instance finishes.
 */
public final class SoundPreview {
    private static SoundInstance current;
    private static ResourceLocation looping;

    private SoundPreview() {
    }

    public static boolean play(String soundId, float pitch, float volume) {
        SoundEvent event = resolve(soundId);
        if (event == null) {
            return false;
        }
        stop();
        current = SimpleSoundInstance.forUI(event, pitch, volume);
        Minecraft.getInstance().getSoundManager().play(current);
        return true;
    }

    public static boolean playLooping(String soundId, float pitch, float volume) {
        if (!play(soundId, pitch, volume)) {
            return false;
        }
        looping = resolveId(soundId);
        return true;
    }

    public static void stop() {
        if (current != null) {
            Minecraft.getInstance().getSoundManager().stop(current);
            current = null;
        }
        looping = null;
    }

    public static boolean isPlaying() {
        return current != null && Minecraft.getInstance().getSoundManager().isActive(current);
    }

    /** Called from the owning screen's tick to keep a loop alive. */
    public static void tick() {
        if (looping != null && !isPlaying()) {
            SoundEvent event = BuiltInRegistries.SOUND_EVENT.get(looping);
            if (event != null) {
                current = SimpleSoundInstance.forUI(event, lastPitch, lastVolume);
                Minecraft.getInstance().getSoundManager().play(current);
            } else {
                looping = null;
            }
        }
    }

    private static float lastPitch = 1.0f;
    private static float lastVolume = 1.0f;

    public static void setPitchVolume(float pitch, float volume) {
        lastPitch = pitch;
        lastVolume = volume;
    }

    private static SoundEvent resolve(String soundId) {
        ResourceLocation id = resolveId(soundId);
        return id == null ? null : BuiltInRegistries.SOUND_EVENT.get(id);
    }

    private static ResourceLocation resolveId(String soundId) {
        if (soundId == null || soundId.isBlank()) {
            return null;
        }
        try {
            return soundId.contains(":") ? ResourceLocation.parse(soundId) : ResourceLocation.parse(soundId);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
