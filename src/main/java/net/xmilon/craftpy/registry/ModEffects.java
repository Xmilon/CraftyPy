package net.xmilon.craftpy.registry;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.entity.SlottedEffect;

/** The effect slot pool ({@code pceffect_N}), registered once at startup. */
public final class ModEffects {
    public static final DeferredRegister<MobEffect> EFFECTS = DeferredRegister.create(Registries.MOB_EFFECT, CraftPyLog.MODID);
    public static final List<DeferredHolder<MobEffect, SlottedEffect>> SLOTS = new ArrayList<>();

    static {
        for (int i = 0; i < SlotRegistry.EFFECT_SLOTS; i++) {
            final int index = i;
            SLOTS.add(EFFECTS.register("pceffect_" + i, () -> new SlottedEffect(index)));
        }
    }

    private ModEffects() {
    }
}
