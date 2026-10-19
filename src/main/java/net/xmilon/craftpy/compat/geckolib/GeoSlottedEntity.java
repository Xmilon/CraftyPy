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
    private AnimationController<GeoSlottedEntity> events;

    public GeoSlottedEntity(EntityType<? extends SlottedEntity> type, Level level, SlotRegistry.Kind kind, int index) {
        super(type, level, kind, index);
    }

    public static SlottedEntity create(EntityType<? extends SlottedEntity> type, Level level, SlotRegistry.Kind kind, int index) {
        return new GeoSlottedEntity(type, level, kind, index);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "craftpy", 5, state -> {
            String name = animation(state.isMoving() ? isSprinting() || getDeltaMovement().horizontalDistanceSqr() > .04 ? "run" : "walk" : "idle");
            if (name == null && state.isMoving()) name = animation("walk");
            if (name == null) {
                name = animation("idle");
            }
            return name == null ? PlayState.STOP : state.setAndContinue(RawAnimation.begin().thenLoop(name));
        }));
        events = new AnimationController<>(this, "craftpy_events", 0, state -> PlayState.STOP);
        updateTriggers(); controllers.add(events);
    }

    private void updateTriggers() {
        for (String name : java.util.List.of("attack", "cast", "hurt", "death")) {
            String clip = net.xmilon.craftpy.content.Defs.s(look().clips(), name, name);
            events.triggerableAnim(name, RawAnimation.begin().thenPlay(clip));
        }
    }

    @Override public void onSyncedDataUpdated(net.minecraft.network.syncher.EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key); if (events != null) updateTriggers();
    }

    @Override public boolean playAnimation(String event) {
        if (level().isClientSide || !look().geckoModel() || !look().clips().has(event)
                || !java.util.Set.of("attack", "cast", "hurt", "death").contains(event)) return false;
        triggerAnim("craftpy_events", event); return true;
    }

    /** The full name of this mob's animation whose name ends in {@code suffix}, or null. */
    private String animation(String suffix) {
        String configured = net.xmilon.craftpy.content.Defs.s(look().clips(), suffix, "");
        ResourceLocation file = ResourceLocation.tryParse(look().animation());
        BakedAnimations baked = file == null ? null : GeckoLibCache.getBakedAnimations().get(file);
        if (baked == null) {
            return null;
        }
        if (!configured.isBlank()) return baked.animations().containsKey(configured) ? configured : null;
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
