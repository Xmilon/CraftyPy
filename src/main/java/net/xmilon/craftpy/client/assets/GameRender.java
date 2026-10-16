package net.xmilon.craftpy.client.assets;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.xmilon.craftpy.client.IdeHost;
import net.xmilon.craftpy.client.PixelBuffer;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

/**
 * Pictures of blocks, items and mobs drawn by the game's own renderer (vanilla and every mod's models, block entity
 * renderers like chests, tint colours, entity models), into an offscreen buffer that is read back as pixels. Only
 * while the game runs: the standalone IDE has no renderer and keeps the JSON model view.
 */
public final class GameRender {
    public enum Kind { BLOCK, ITEM, MOB }

    private static final int SIZE = 256; // drawn at 256, shrunk to 128 so edges are smooth (the target has no MSAA)
    private static final int FULL_BRIGHT = 0xF000F0;

    private GameRender() {
    }

    public static boolean available() {
        return GameResources.inGame() && Minecraft.getInstance().level != null;
    }

    /** Ids of every mob type (vanilla and mods), sorted. */
    public static List<String> mobIds() {
        List<String> ids = new ArrayList<>();
        BuiltInRegistries.ENTITY_TYPE.keySet().forEach(id -> ids.add(id.toString()));
        ids.sort(null);
        return ids;
    }

    /** Draws one thing turned by yaw / pitch (degrees); completes on the client thread, fails with a readable message. */
    public static CompletableFuture<PixelBuffer> render(Kind kind, String id, double yaw, double pitch) {
        CompletableFuture<PixelBuffer> done = new CompletableFuture<>();
        IdeHost.get().execute(() -> {
            try {
                done.complete(draw(kind, id, (float) yaw, (float) pitch));
            } catch (Throwable e) { // a mod renderer that throws must not break the frame or leave the view waiting
                done.completeExceptionally(e);
            }
        });
        return done;
    }

    private static PixelBuffer draw(Kind kind, String id, float yaw, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            throw new IllegalStateException("Join a world to see the game's own render");
        }
        ResourceLocation location = ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
        if (location == null) {
            throw new IllegalArgumentException("Not an id: " + id);
        }
        TextureTarget target = new TextureTarget(SIZE, SIZE, true, Minecraft.ON_OSX);
        Matrix4fStack view = RenderSystem.getModelViewStack();
        RenderSystem.backupProjectionMatrix();
        view.pushMatrix();
        try {
            target.setClearColor(0, 0, 0, 0);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(-1, 1, -1, 1, -100, 100), VertexSorting.ORTHOGRAPHIC_Z);
            view.identity();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.enableDepthTest();
            Lighting.setupFor3DItems();
            PoseStack pose = new PoseStack();
            pose.mulPose(Axis.XP.rotationDegrees(pitch));
            pose.mulPose(Axis.YP.rotationDegrees(yaw));
            var buffers = mc.renderBuffers().bufferSource();
            switch (kind) {
                case BLOCK -> {
                    var block = BuiltInRegistries.BLOCK.getOptional(location)
                            .orElseThrow(() -> new IllegalArgumentException("No block " + location));
                    if (block == Blocks.AIR) {
                        throw new IllegalArgumentException("Air has nothing to draw");
                    }
                    pose.scale(1.05f, 1.05f, 1.05f);
                    pose.translate(-0.5f, -0.5f, -0.5f);
                    mc.getBlockRenderer().renderSingleBlock(block.defaultBlockState(), pose, buffers, FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                            net.neoforged.neoforge.client.model.data.ModelData.EMPTY, null);
                }
                case ITEM -> {
                    var item = BuiltInRegistries.ITEM.getOptional(location)
                            .orElseThrow(() -> new IllegalArgumentException("No item " + location));
                    if (item == Items.AIR) {
                        throw new IllegalArgumentException("Air has nothing to draw");
                    }
                    pose.scale(1.6f, 1.6f, 1.6f);
                    mc.getItemRenderer().renderStatic(new ItemStack(item), ItemDisplayContext.FIXED, FULL_BRIGHT,
                            OverlayTexture.NO_OVERLAY, pose, buffers, mc.level, 0);
                }
                case MOB -> {
                    var type = BuiltInRegistries.ENTITY_TYPE.getOptional(location)
                            .orElseThrow(() -> new IllegalArgumentException("No mob " + location));
                    Entity entity = type.create(mc.level);
                    if (entity == null) {
                        throw new IllegalArgumentException(location + " cannot be created on its own (players, some projectiles)");
                    }
                    if (entity instanceof LivingEntity living) {
                        living.yBodyRot = living.yBodyRotO = 0;
                        living.yHeadRot = living.yHeadRotO = 0;
                    }
                    float height = Math.max(0.2f, entity.getBbHeight());
                    float scale = 1.5f / Math.max(height, entity.getBbWidth() * 1.4f);
                    pose.scale(scale, scale, scale);
                    pose.translate(0, -height / 2, 0);
                    var dispatcher = mc.getEntityRenderDispatcher();
                    dispatcher.setRenderShadow(false);
                    try {
                        dispatcher.render(entity, 0, 0, 0, 0, 1, pose, buffers, FULL_BRIGHT);
                    } finally {
                        dispatcher.setRenderShadow(true);
                    }
                }
            }
            buffers.endBatch();
            try (NativeImage image = new NativeImage(SIZE, SIZE, false)) {
                RenderSystem.bindTexture(target.getColorTextureId());
                image.downloadTexture(0, false);
                image.flipY();
                return shrink(image);
            }
        } finally {
            view.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
            target.destroyBuffers();
            mc.getMainRenderTarget().bindWrite(true);
        }
    }

    /** 2x2 box filter, ABGR (NativeImage) to ARGB. */
    private static PixelBuffer shrink(NativeImage image) {
        int half = SIZE / 2;
        int[] out = new int[half * half];
        for (int y = 0; y < half; y++) {
            for (int x = 0; x < half; x++) {
                int a = 0, r = 0, g = 0, b = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int p = image.getPixelRGBA(x * 2 + dx, y * 2 + dy);
                        int alpha = p >>> 24;
                        a += alpha;
                        r += (p & 0xFF) * alpha;
                        g += ((p >> 8) & 0xFF) * alpha;
                        b += ((p >> 16) & 0xFF) * alpha;
                    }
                }
                out[y * half + x] = a == 0 ? 0 : (a / 4) << 24 | (r / a) << 16 | (g / a) << 8 | (b / a);
            }
        }
        return new PixelBuffer(half, half, out);
    }
}
