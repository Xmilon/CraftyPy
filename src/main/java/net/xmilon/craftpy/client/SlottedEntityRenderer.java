package net.xmilon.craftpy.client;

import java.util.LinkedHashMap;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.model.ChickenModel;
import net.minecraft.client.model.CowModel;
import net.minecraft.client.model.CreeperModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PigModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.SlimeModel;
import net.minecraft.client.model.SpiderModel;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.xmilon.craftpy.entity.SlottedEntity;

/**
 * Draws a Python mob with the body its definition names: a vanilla one ({@code humanoid}, {@code player}, {@code pig},
 * {@code cow}, {@code chicken}, {@code spider}, {@code creeper}, {@code villager}, {@code slime}) with the mob's texture,
 * or its GeckoLib model when GeckoLib is installed. Unknown names, or a GeckoLib model without GeckoLib, draw as humanoid.
 */
public class SlottedEntityRenderer extends EntityRenderer<SlottedEntity> {
    /** The vanilla bodies a mob can use, in the order the docs list them. */
    public static final String[] MODELS = {"humanoid", "player", "pig", "cow", "chicken", "spider", "creeper", "villager", "slime"};

    private final Map<String, Body> bodies = new LinkedHashMap<>();
    private final EntityRenderer<SlottedEntity> gecko;

    public SlottedEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
        bodies.put("humanoid", new Body(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.ZOMBIE)), 0.5f, "zombie/zombie"));
        bodies.put("player", new Body(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5f, "player/wide/steve"));
        bodies.put("pig", new Body(context, new PigModel<>(context.bakeLayer(ModelLayers.PIG)), 0.7f, "pig/pig"));
        bodies.put("cow", new Body(context, new CowModel<>(context.bakeLayer(ModelLayers.COW)), 0.7f, "cow/cow"));
        bodies.put("chicken", new Body(context, new ChickenModel<>(context.bakeLayer(ModelLayers.CHICKEN)), 0.3f, "chicken"));
        bodies.put("spider", new Body(context, new SpiderModel<>(context.bakeLayer(ModelLayers.SPIDER)), 0.8f, "spider/spider"));
        bodies.put("creeper", new Body(context, new CreeperModel<>(context.bakeLayer(ModelLayers.CREEPER)), 0.5f, "creeper/creeper"));
        bodies.put("villager", new Body(context, new VillagerModel<>(context.bakeLayer(ModelLayers.VILLAGER)), 0.5f, "villager/villager"));
        bodies.put("slime", new Body(context, new SlimeModel<>(context.bakeLayer(ModelLayers.SLIME_OUTER)), 0.25f, "slime/slime"));
        gecko = net.neoforged.fml.ModList.get().isLoaded("geckolib")
                ? net.xmilon.craftpy.compat.geckolib.GeoSlottedRenderer.create(context) : null;
    }

    private EntityRenderer<SlottedEntity> pick(SlottedEntity entity) {
        SlottedEntity.Look look = entity.look();
        if (look.geckoModel() && gecko != null && net.xmilon.craftpy.compat.geckolib.GeoSlottedRenderer.ready(entity)) {
            return gecko;
        }
        return bodies.getOrDefault(look.model(), bodies.get("humanoid"));
    }

    @Override
    public void render(SlottedEntity entity, float yaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffer,
                       int packedLight) {
        pick(entity).render(entity, yaw, partialTicks, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(SlottedEntity entity) {
        return pick(entity).getTextureLocation(entity);
    }

    /** One vanilla body: its model, the vanilla skin used when the mob has no texture, the scale of the look. */
    private static final class Body extends MobRenderer<SlottedEntity, EntityModel<SlottedEntity>> {
        private final ResourceLocation skin;

        Body(EntityRendererProvider.Context context, EntityModel<SlottedEntity> model, float shadow, String vanillaSkin) {
            super(context, model, shadow);
            this.skin = ResourceLocation.withDefaultNamespace("textures/entity/" + vanillaSkin + ".png");
        }

        @Override
        public ResourceLocation getTextureLocation(SlottedEntity entity) {
            return entity.texture(skin);
        }

        @Override
        protected void scale(SlottedEntity entity, PoseStack poseStack, float partialTick) {
            float scale = entity.look().scale();
            poseStack.scale(scale, scale, scale);
        }
    }
}
