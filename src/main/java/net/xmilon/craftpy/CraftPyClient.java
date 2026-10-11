package net.xmilon.craftpy;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.xmilon.craftpy.client.assets.AssetIndex;
import net.xmilon.craftpy.client.assets.AssetUploader;
import net.xmilon.craftpy.client.screen.ComputerScreen;
import net.xmilon.craftpy.client.screen.ProjectChooserScreen;
import net.xmilon.craftpy.registry.ModMenus;

import org.lwjgl.glfw.GLFW;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = CraftPy.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = CraftPy.MODID, value = Dist.CLIENT)
public class CraftPyClient {
    public CraftPyClient(ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        CraftPy.LOGGER.info("CraftPy client setup for {}", Minecraft.getInstance().getUser().getName());
        event.enqueueWork(() -> {
            long window = Minecraft.getInstance().getWindow().getWindow();
            GLFW.glfwSetDropCallback(window, (handle, count, names) -> {
                java.util.List<java.nio.file.Path> paths = AssetUploader.fromDropArgs(count, names);
                if (Minecraft.getInstance().screen instanceof ComputerScreen computer) {
                    computer.handleDroppedAssets(paths);
                }
            });
        });
    }

    @SubscribeEvent
    static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        // The computer opens its own dedicated project window; the IDE is a separate screen.
        event.register(ModMenus.COMPUTER.get(), ProjectChooserScreen::new);
    }

    @SubscribeEvent
    static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        // Keeps the asset catalogue in sync with resource (and dynamic project) reloads.
        event.registerReloadListener(AssetIndex.get());
    }
}
