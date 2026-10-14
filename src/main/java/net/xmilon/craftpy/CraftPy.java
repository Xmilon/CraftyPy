package net.xmilon.craftpy;

import net.xmilon.craftpy.command.CraftPyCommand;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.xmilon.craftpy.dynamics.DynamicPackManager;
import net.xmilon.craftpy.lifecycle.ProjectController;
import net.xmilon.craftpy.network.CraftPyNetwork;
import net.xmilon.craftpy.project.ProjectManager;
import net.xmilon.craftpy.registry.ModBlockEntities;
import net.xmilon.craftpy.registry.ModBlocks;
import net.xmilon.craftpy.registry.ModCreativeTabs;
import net.xmilon.craftpy.registry.ModItems;
import net.xmilon.craftpy.registry.ModMenus;
import net.xmilon.craftpy.util.CraftPyExecutors;

@Mod(CraftPy.MODID)
public class CraftPy {
    public static final String MODID = "craftpy";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CraftPy(IEventBus modEventBus, ModContainer modContainer) {
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModMenus.MENUS.register(modEventBus);
        ModCreativeTabs.TABS.register(modEventBus);
        modEventBus.register(net.xmilon.craftpy.network.CraftPyNetwork.class);
        modEventBus.addListener(DynamicPackManager::registerSources);

        NeoForge.EVENT_BUS.register(this);
        // Project content is /craftpy give only - blocks it from vanilla /give.
        NeoForge.EVENT_BUS.register(net.xmilon.craftpy.command.CommandGuard.class);
        NeoForge.EVENT_BUS.register(net.xmilon.craftpy.command.CraftPyPermissions.class);
        NeoForge.EVENT_BUS.register(net.xmilon.craftpy.lifecycle.EventBridge.class);
        NeoForge.EVENT_BUS.register(net.xmilon.craftpy.lifecycle.RestartManager.class);

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        
        net.xmilon.craftpy.project.PyForgePaths.ensureLayout();
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        // All project IO is owned by the server (integrated server = same instance folder).
        ProjectManager.init();
        net.xmilon.craftpy.project.ModuleLoader.discoverAndLoad();
        LOGGER.info("CraftPy project manager ready");
        // Rebuild generated packs and re-apply previously active projects.
        ProjectController.startup(event.getServer());
        for (String projectId : net.xmilon.craftpy.registry.SlotRegistry.activeProjectIds()) {
            ProjectController.get().load(projectId, event.getServer());
        }
        // Probe the Python worker off-thread; logs the discovered runtime version.
        net.xmilon.craftpy.python.PyRuntime.get().ping()
                .thenAccept(message -> LOGGER.info(message))
                .exceptionally(error -> {
                    LOGGER.error("Python worker probe failed", error);
                    return null;
                });
    }

    @SubscribeEvent
    public void onDatapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) {
            CraftPyNetwork.sendActive(event.getPlayer());
        } else {
            CraftPyNetwork.broadcastActive(event.getPlayerList().getServer());
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        ProjectManager.shutdown();
        net.xmilon.craftpy.python.PyRuntime.shutdown();
    }

    @SubscribeEvent
    public void onRegisterCommands(net.neoforged.neoforge.event.RegisterCommandsEvent event) {
        CraftPyCommand.register(event.getDispatcher(), event.getBuildContext());
    }
}
