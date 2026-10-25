package net.xmilon.craftpy.content;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

/** A sound by id: the game's own, or one a project names in its sounds.json (never registered, played by id). */
public final class SoundIds {
    private SoundIds() {
    }

    public static SoundEvent of(ResourceLocation id) {
        return BuiltInRegistries.SOUND_EVENT.getOptional(id).orElseGet(() -> SoundEvent.createVariableRangeEvent(id));
    }

    /** Null for an empty or malformed id. */
    public static SoundEvent of(String id) {
        ResourceLocation location = id == null || id.isBlank() ? null : ResourceLocation.tryParse(id);
        return location == null ? null : of(location);
    }
}
