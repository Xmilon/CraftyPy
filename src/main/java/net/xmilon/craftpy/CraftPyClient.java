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
@Mod(value = CraftPy.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = CraftPy.MODID, value = Dist.CLIENT)
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
    static void onClientSetup(FMLClientSetupEvent event) {
        CraftPy.LOGGER.info("CraftPy client setup for {}", Minecraft.getInstance().getUser().getName());
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
    static void onInput(InputEvent.Key event) {
        if (OPEN_IDE_KEY.consumeClick()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.player.hasPermissions(2)) { // Cheats/admin only
                if (mc.screen == null) {
                    mc.setScreen(CraftPyFragment.createScreen(CraftPyFragment.PAGE_HOME));
                }
            }
        }
    }
}
