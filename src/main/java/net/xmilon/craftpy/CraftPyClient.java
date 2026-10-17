package net.xmilon.craftpy;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.xmilon.craftpy.client.assets.AssetIndex;
import net.xmilon.craftpy.client.assets.AssetUploader;
import net.xmilon.craftpy.client.ClientProjectStore;
import net.xmilon.craftpy.client.mui.CraftPyFragment;
import net.xmilon.craftpy.registry.ModMenus;

import org.lwjgl.glfw.GLFW;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = CraftPyLog.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = CraftPyLog.MODID, value = Dist.CLIENT)
public class CraftPyClient {
    public static final String KEY_CATEGORY = "key.categories.craftpy";
    public static final KeyMapping OPEN_IDE_KEY = new KeyMapping(
            "key.craftpy.open_ide",
            GLFW.GLFW_KEY_I,
            KEY_CATEGORY
    );

    public CraftPyClient(ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onTooltip(net.neoforged.neoforge.event.entity.player.ItemTooltipEvent event) {
        net.xmilon.craftpy.client.ContentTooltip.onTooltip(event);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        CraftPyLog.LOGGER.info("CraftPy client setup for {}", Minecraft.getInstance().getUser().getName());
        net.xmilon.craftpy.client.IdeHost.install(new net.xmilon.craftpy.client.McHost());
        event.enqueueWork(() -> {
            long window = Minecraft.getInstance().getWindow().getWindow();
            final org.lwjgl.glfw.GLFWDropCallback[] previous = new org.lwjgl.glfw.GLFWDropCallback[1];
            previous[0] = GLFW.glfwSetDropCallback(window, (handle, count, names) -> {
                // Files dropped on a CraftPy screen are uploaded to the open project; every other screen keeps the
                // callback that was there (vanilla pack drag and drop).
                if (!(Minecraft.getInstance().screen instanceof icyllis.modernui.mc.MuiScreen mui
                        && mui.getFragment() instanceof CraftPyFragment)) {
                    if (previous[0] != null) {
                        previous[0].invoke(handle, count, names);
                    }
                    return;
                }
                java.util.List<java.nio.file.Path> paths = AssetUploader.fromDropArgs(count, names);
                ClientProjectStore store = ClientProjectStore.getInstance();
                String projectId = store.activeProjectId();
                if (projectId == null) {
                    store.append("Open a project before dropping assets");
                    return;
                }
                AssetUploader.uploadAll(projectId, paths, net.xmilon.craftpy.assets.AssetKind.TEXTURE);
            });
        });
        // Register key mapping
        event.enqueueWork(() -> {
            var opts = Minecraft.getInstance().options;
            var mappings = new java.util.ArrayList<KeyMapping>(java.util.Arrays.asList(opts.keyMappings));
            mappings.add(OPEN_IDE_KEY);
            opts.keyMappings = mappings.toArray(new KeyMapping[0]);
        });
    }

    @SubscribeEvent
    static void onRegisterRenderers(net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
        net.xmilon.craftpy.registry.ModEntities.MOBS.forEach(type ->
                event.registerEntityRenderer(type.get(), net.xmilon.craftpy.client.SlottedEntityRenderer::new));
        net.xmilon.craftpy.registry.ModEntities.MONSTERS.forEach(type ->
                event.registerEntityRenderer(type.get(), net.xmilon.craftpy.client.SlottedEntityRenderer::new));
    }

    /** The spawn egg takes the colors of the mob it names (stored on the stack by /craftpy give ... egg). */
    @SubscribeEvent
    static void onItemColors(net.neoforged.neoforge.client.event.RegisterColorHandlersEvent.Item event) {
        event.register((stack, tint) -> {
            var data = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
            int color = tint == 0 ? (data.contains("craftpy_egg") ? data.getInt("craftpy_egg") : 0x3d8f5a)
                    : (data.contains("craftpy_egg_spots") ? data.getInt("craftpy_egg_spots") : 0xc9e86b);
            return 0xFF000000 | color;
        }, net.xmilon.craftpy.registry.ModItems.SPAWN_EGG.get());
    }

    @SubscribeEvent
    static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        // The computer block opens the CraftPy home screen (Modern UI).
        event.register(ModMenus.COMPUTER.get(),
                icyllis.modernui.mc.neoforge.MenuScreenFactory.create(menu -> new CraftPyFragment()));
    }

    @SubscribeEvent
    static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        // Keeps the asset catalogue in sync with resource (and dynamic project) reloads.
        event.registerReloadListener(AssetIndex.get());
    }

    @SubscribeEvent
    static void onRegisterGuiLayers(net.neoforged.neoforge.client.event.RegisterGuiLayersEvent event) {
        event.registerAboveAll(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(CraftPyLog.MODID, "hud"),
                net.xmilon.craftpy.client.ClientHud::render);
    }

    // ---- client-side Python (HUD, keys, client events): everything is handed to the background thread

    @SubscribeEvent
    static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        net.xmilon.craftpy.client.ClientPython.get().onTick();
    }

    @SubscribeEvent
    static void onJoin(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingIn event) {
        net.xmilon.craftpy.client.ClientPython python = net.xmilon.craftpy.client.ClientPython.get();
        python.loadModules();
        python.event("client_join", new com.google.gson.JsonObject());
    }

    @SubscribeEvent
    static void onLeave(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        net.xmilon.craftpy.client.ClientPython python = net.xmilon.craftpy.client.ClientPython.get();
        python.event("client_leave", new com.google.gson.JsonObject());
        python.shutdown();
    }

    @SubscribeEvent
    static void onChat(net.neoforged.neoforge.client.event.ClientChatReceivedEvent event) {
        com.google.gson.JsonObject context = new com.google.gson.JsonObject();
        context.addProperty("message", event.getMessage().getString());
        net.xmilon.craftpy.client.ClientPython.get().event("chat_received", context);
    }

    @SubscribeEvent
    static void onScreen(net.neoforged.neoforge.client.event.ScreenEvent.Opening event) {
        com.google.gson.JsonObject context = new com.google.gson.JsonObject();
        context.addProperty("screen", event.getNewScreen().getClass().getSimpleName());
        net.xmilon.craftpy.client.ClientPython.get().event("screen_open", context);
    }

    @SubscribeEvent
    static void onInput(InputEvent.Key event) {
        if (event.getAction() == org.lwjgl.glfw.GLFW.GLFW_PRESS && Minecraft.getInstance().screen == null) {
            String combo = net.xmilon.craftpy.client.KeyCombo.name(event.getKey(), event.getModifiers());
            if (combo != null && net.xmilon.craftpy.client.ClientHost.isBound(combo)) {
                net.xmilon.craftpy.client.ClientPython.get().key(combo);
            }
        }
        if (OPEN_IDE_KEY.consumeClick()) {
            Minecraft mc = Minecraft.getInstance();
            // admins, or the owner of a singleplayer world (the server checks again on every request)
            if (mc.player != null && (mc.player.hasPermissions(2) || mc.hasSingleplayerServer())) {
                if (mc.screen == null) {
                    boolean project = net.xmilon.craftpy.client.ClientProjectStore.getInstance().selectedId() != null;
                    mc.setScreen(CraftPyFragment.createScreen(project ? CraftPyFragment.PAGE_EDITOR : CraftPyFragment.PAGE_HOME));
                }
            }
        }
    }
}
