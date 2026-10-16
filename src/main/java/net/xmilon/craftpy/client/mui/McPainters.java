package net.xmilon.craftpy.client.mui;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@link McCanvas} painters: a block, an item or a mob drawn by the game's own renderers every frame (vanilla and
 * mod models, block entity renderers, GeckoLib items and mobs, animated textures), turned by dragging and zoomed by
 * the wheel. Render thread for painting; the turn and zoom are set from the UI thread.
 */
final class McPainters {
    enum Kind { BLOCK, ITEM, MOB }

    private static final int FULL_BRIGHT = 0xF000F0;

    private McPainters() {
    }

    /** Ids of every mob type of the game and its mods, sorted. */
    static List<String> mobIds() {
        List<String> ids = new ArrayList<>();
        BuiltInRegistries.ENTITY_TYPE.keySet().forEach(id -> ids.add(id.toString()));
        ids.sort(null);
        return ids;
    }

    static ResourceLocation id(String id) {
        return ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
    }

    /** The item for an id, or null (air, unknown). */
    static ItemStack stack(String id) {
        ResourceLocation location = id(id);
        var item = location == null ? null : BuiltInRegistries.ITEM.getOptional(location).orElse(null);
        return item == null || item == Items.AIR ? null : new ItemStack(item);
    }

    /** A turnable 3D view. {@code onProblem} gets a message when the thing cannot be drawn (UI thread is not assumed). */
    static Turntable turntable(Kind kind, String id, java.util.function.Consumer<String> onProblem) {
        return new Turntable(kind, id, onProblem);
    }

    static final class Turntable implements McCanvas.Painter {
        private final Kind kind;
        private final String id;
        private final java.util.function.Consumer<String> onProblem;
        private volatile double yaw = 215;  // fronts face south: turn them towards the viewer, a little from the side
        private volatile double pitch = 25;
        private volatile double zoom = 1;
        private boolean resolved;
        private BlockState block;
        private ItemStack item;
        private Entity mob;

        private Turntable(Kind kind, String id, java.util.function.Consumer<String> onProblem) {
            this.kind = kind;
            this.id = id;
            this.onProblem = onProblem;
        }

        @Override
        public void drag(double dx, double dy) {
            yaw += dx * 1.5;
            pitch = Math.max(-89, Math.min(89, pitch + dy * 1.5));
        }

        @Override
        public void scroll(double amount) {
            zoom = Math.max(0.2, Math.min(6, zoom * (amount > 0 ? 1.15 : 1 / 1.15)));
        }

        /** First paint (render thread): finds the block / item / mob; a problem is reported once. */
        private void resolve() {
            resolved = true;
            Minecraft mc = Minecraft.getInstance();
            ResourceLocation location = id(id);
            String problem = null;
            if (location == null) {
                problem = "Not an id: " + id;
            } else {
                switch (kind) {
                    case BLOCK -> {
                        var found = BuiltInRegistries.BLOCK.getOptional(location).orElse(null);
                        if (found == null || found == Blocks.AIR) {
                            problem = "No block " + location;
                        } else {
                            block = found.defaultBlockState();
                        }
                    }
                    case ITEM -> {
                        item = stack(location.toString());
                        if (item == null) {
                            problem = "No item " + location;
                        }
                    }
                    case MOB -> {
                        var type = BuiltInRegistries.ENTITY_TYPE.getOptional(location).orElse(null);
                        if (type == null) {
                            problem = "No mob " + location;
                        } else if (mc.level == null) {
                            problem = "Join a world to see mobs";
                        } else {
                            mob = type.create(mc.level);
                            if (mob == null) {
                                problem = location + " cannot be created on its own (players, some projectiles)";
                            } else if (mob instanceof LivingEntity living) {
                                living.yBodyRot = living.yBodyRotO = 0;
                                living.yHeadRot = living.yHeadRotO = 0;
                            }
                        }
                    }
                }
            }
            if (problem != null) {
                onProblem.accept(problem);
            }
        }

        @Override
        public void paint(GuiGraphics g, int width, int height, int mouseX, int mouseY, float partialTick) {
            if (!resolved) {
                resolve();
            }
            Minecraft mc = Minecraft.getInstance();
            PoseStack pose = g.pose();
            double size = Math.min(width, height) * zoom;
            pose.pushPose();
            pose.translate(width / 2.0, height / 2.0, 150);
            if (kind == Kind.MOB && mob != null) {
                float tall = Math.max(0.3f, mob.getBbHeight());
                float s = (float) (size * 0.62 / Math.max(tall, mob.getBbWidth() * 1.3f));
                pose.scale(s, -s, s);
                pose.mulPose(Axis.XP.rotationDegrees((float) -pitch));
                pose.mulPose(Axis.YP.rotationDegrees((float) yaw));
                pose.translate(0, -tall / 2, 0);
                mob.tickCount = (int) (System.currentTimeMillis() / 50); // GeckoLib and vanilla idle animations move
                Lighting.setupForEntityInInventory();
                var dispatcher = mc.getEntityRenderDispatcher();
                dispatcher.setRenderShadow(false);
                try {
                    dispatcher.render(mob, 0, 0, 0, 0, partialTick, pose, g.bufferSource(), FULL_BRIGHT);
                    g.flush();
                } finally {
                    dispatcher.setRenderShadow(true);
                }
            } else if (block != null || item != null) {
                float s = (float) (size * (kind == Kind.BLOCK ? 0.48 : 0.62));
                pose.scale(s, -s, s);
                pose.mulPose(Axis.XP.rotationDegrees((float) -pitch));
                pose.mulPose(Axis.YP.rotationDegrees((float) yaw));
                Lighting.setupFor3DItems();
                if (kind == Kind.BLOCK) {
                    pose.translate(-0.5f, -0.5f, -0.5f);
                    mc.getBlockRenderer().renderSingleBlock(block, pose, g.bufferSource(), FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                            net.neoforged.neoforge.client.model.data.ModelData.EMPTY, null);
                } else {
                    mc.getItemRenderer().renderStatic(item, ItemDisplayContext.FIXED, FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                            pose, g.bufferSource(), mc.level, 0);
                }
                g.flush();
            }
            pose.popPose();
            Lighting.setupFor3DItems(); // what GUI item rendering expects afterwards
        }
    }
}
