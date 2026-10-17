package net.xmilon.craftpy.compat.geckolib;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.xmilon.craftpy.entity.SlottedEntity;
import net.xmilon.craftpy.registry.SlotRegistry;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.loading.object.BakedAnimations;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * A Python mob that can be drawn by GeckoLib (only created when GeckoLib is installed). It plays the animation of its
 * animation file whose name ends in {@code walk} while it moves and the one ending in {@code idle} otherwise
 * ({@code animation.goblin.walk}, {@code idle}...). A mob with a vanilla body ignores all this.
 */
public class GeoSlottedEntity extends SlottedEntity implements GeoEntity {
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public GeoSlottedEntity(EntityType<? extends SlottedEntity> type, Level level, SlotRegistry.Kind kind, int index) {
        super(type, level, kind, index);
    }

    public static SlottedEntity create(EntityType<? extends SlottedEntity> type, Level level, SlotRegistry.Kind kind, int index) {
        return new GeoSlottedEntity(type, level, kind, index);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "craftpy", 5, state -> {
            String name = animation(state.isMoving() ? "walk" : "idle");
            if (name == null) {
                name = animation("idle");
            }
            return name == null ? PlayState.STOP : state.setAndContinue(RawAnimation.begin().thenLoop(name));
        }));
    }

    /** The full name of this mob's animation whose name ends in {@code suffix}, or null. */
    private String animation(String suffix) {
        ResourceLocation file = ResourceLocation.tryParse(look().animation());
        BakedAnimations baked = file == null ? null : GeckoLibCache.getBakedAnimations().get(file);
        if (baked == null) {
            return null;
        }
        for (String name : baked.animations().keySet()) {
            if (name.equals(suffix) || name.endsWith("." + suffix)) {
                return name;
            }
        }
        return null;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}
