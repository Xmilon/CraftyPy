package net.xmilon.craftpy.compat.geckolib;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.xmilon.craftpy.block.SlottedItem;
import net.xmilon.craftpy.content.Defs;
import net.xmilon.craftpy.lifecycle.EventBridge;
import net.xmilon.craftpy.registry.SlotRegistry;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.SingletonGeoAnimatable;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.loading.object.BakedAnimations;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * A Python item that may be drawn by GeckoLib (only created when GeckoLib is installed; {@code Item(geo="damocles")}): the
 * model is {@code geo/<name>.geo.json} of the project, its animations {@code animations/<name>.animation.json}. The clip
 * ending in {@code idle} loops while the item is shown; clips ending in {@code use} and {@code attack} play on a right click
 * and on a hit. An item without {@code geo} behaves like any other.
 */
public class GeoSlottedItem extends SlottedItem implements GeoItem {
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public GeoSlottedItem(int slotIndex, Properties properties) {
        super(slotIndex, properties);
        SingletonGeoAnimatable.registerSyncedAnimatable(this);
    }

    public static SlottedItem create(int slotIndex, Properties properties) {
        return new GeoSlottedItem(slotIndex, properties);
    }

    /** The project that owns this slot, or "craftpy". */
    private String namespace() {
        String id = EventBridge.projectId(SlotRegistry.holderForItem(slotIndex()));
        int colon = id.indexOf(':');
        return colon > 0 ? id.substring(0, colon) : "craftpy";
    }

    public boolean hasGeo() {
        Defs.ItemDef def = definition();
        return def != null && def.geo != null && !def.geo.isBlank();
    }

    public ResourceLocation geoModel() {
        return ResourceLocation.fromNamespaceAndPath(namespace(), "geo/" + definition().geo + ".geo.json");
    }

    public ResourceLocation geoAnimations() {
        return ResourceLocation.fromNamespaceAndPath(namespace(), "animations/" + definition().geo + ".animation.json");
    }

    public ResourceLocation geoTexture() {
        Defs.ItemDef def = definition();
        String texture = def.geoTexture == null || def.geoTexture.isBlank() ? def.geo + "_geo" : def.geoTexture;
        if (texture.endsWith(".png")) texture = texture.substring(0, texture.length() - 4);
        if (texture.startsWith("textures/")) texture = texture.substring("textures/".length());
        ResourceLocation id = ResourceLocation.tryParse(texture.contains(":") ? texture : namespace() + ":" + texture);
        id = id == null ? ResourceLocation.fromNamespaceAndPath(namespace(), def.geo + "_geo") : id;
        return id.withPath(path -> "textures/" + path + ".png");
    }

    /** The full name of the clip whose name ends in {@code suffix} (or is it), or null when the file has none. */
    private String clip(String suffix) {
        if (!hasGeo()) return null;
        BakedAnimations baked = GeckoLibCache.getBakedAnimations().get(geoAnimations());
        if (baked == null) return null;
        for (String name : baked.animations().keySet()) {
            if (name.equals(suffix) || name.endsWith("." + suffix)) return name;
        }
        return null;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        var controller = new AnimationController<GeoSlottedItem>(this, "main", 2, state -> {
            String idle = clip("idle");
            return idle == null ? PlayState.STOP : state.setAndContinue(RawAnimation.begin().thenLoop(idle));
        });
        for (String name : java.util.List.of("use", "attack")) {
            String clip = clip(name);
            if (clip != null) controller.triggerableAnim(name, RawAnimation.begin().thenPlay(clip).thenLoop(clip("idle") == null ? clip : clip("idle")));
        }
        controllers.add(controller);
    }

    private void trigger(Level level, LivingEntity holder, ItemStack stack, String name) {
        if (level instanceof ServerLevel server && hasGeo() && clip(name) != null) {
            triggerAnim(holder, GeoItem.getOrAssignId(stack, server), "main", name);
        }
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        trigger(level, player, player.getItemInHand(hand), "use");
        return super.use(level, player, hand);
    }

    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        trigger(attacker.level(), attacker, stack, "attack");
        return super.hurtEnemy(stack, target, attacker);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}
