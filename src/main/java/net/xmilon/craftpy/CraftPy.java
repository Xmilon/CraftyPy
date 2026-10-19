package net.xmilon.craftpy;

import net.xmilon.craftpy.command.CraftPyCommand;
import org.slf4j.Logger;


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
    public static final String MODID = CraftPyLog.MODID;
    public static final Logger LOGGER = CraftPyLog.LOGGER;

    public CraftPy(IEventBus modEventBus, ModContainer modContainer) {
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        net.xmilon.craftpy.registry.ModEntities.register(modEventBus);
        net.xmilon.craftpy.registry.ModEffects.EFFECTS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        net.xmilon.craftpy.registry.KineticSlots.register(modEventBus);
        net.xmilon.craftpy.content.KineticRecipe.register(modEventBus);
        ModMenus.MENUS.register(modEventBus);
        ModCreativeTabs.TABS.register(modEventBus);
        net.xmilon.craftpy.registry.ModAttachments.TYPES.register(modEventBus);
        modEventBus.register(net.xmilon.craftpy.network.CraftPyNetwork.class);
        modEventBus.register(net.xmilon.craftpy.network.ServerModules.class);
        modEventBus.addListener(DynamicPackManager::registerSources);
        // Curios slots for CraftPy items (only when Curios is installed)
        modEventBus.addListener((net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent event) ->
                event.enqueueWork(net.xmilon.craftpy.lifecycle.CuriosCompat::register));

        NeoForge.EVENT_BUS.register(this);
        // Project content is /craftpy give only - blocks it from vanilla /give.
        NeoForge.EVENT_BUS.register(net.xmilon.craftpy.command.CommandGuard.class);
        NeoForge.EVENT_BUS.register(net.xmilon.craftpy.command.CraftPyPermissions.class);
        NeoForge.EVENT_BUS.register(net.xmilon.craftpy.lifecycle.EventBridge.class);
        NeoForge.EVENT_BUS.register(net.xmilon.craftpy.entity.NativeEntityEvents.class);
        NeoForge.EVENT_BUS.register(net.xmilon.craftpy.content.RotationNetworks.class);
        NeoForge.EVENT_BUS.register(net.xmilon.craftpy.lifecycle.ItemAttributes.class);
        NeoForge.EVENT_BUS.register(net.xmilon.craftpy.lifecycle.RestartManager.class);
        NeoForge.EVENT_BUS.register(net.xmilon.craftpy.server.MediaRelay.class);

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        // what Python may reach outside the game (sites, keys, data folders): the server owner's lists
        modContainer.registerConfig(ModConfig.Type.COMMON, net.xmilon.craftpy.security.AccessConfig.SPEC, "craftpy-access.toml");
        
        net.xmilon.craftpy.project.PyForgePaths.ensureLayout();
        // the first start unpacks the Python shipped in the jar (a few seconds) while the game loads
        net.xmilon.craftpy.util.CraftPyExecutors.runAsync(() -> net.xmilon.craftpy.python.BundledPython.executable());
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        net.xmilon.craftpy.python.PyRuntime.open();
        // All project IO is owned by the server (integrated server = same instance folder).
        ProjectManager.init();
        // Python's storage values of this world, before any module runs
        net.xmilon.craftpy.lifecycle.StorageData.open(event.getServer());
        // which modules are on is part of the world; a world made just now starts with all of them off
        net.xmilon.craftpy.project.ModuleLoader.useStateFile(event.getServer()
                        .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("craftpy").resolve("modules.json"),
                event.getServer().overworld().getGameTime() < 200);
        net.xmilon.craftpy.project.ModuleLoader.discoverAndLoad();
        LOGGER.info("CraftPy project manager ready");
        // Silent: writes system/catalog/<mod>.json for mods that changed, so the IDE can complete their names.
        net.xmilon.craftpy.catalog.CatalogDump.onServerStarted(event.getServer());
        // Rebuild generated packs and re-apply previously active projects.
        ProjectController.startup(event.getServer());
        for (String projectId : net.xmilon.craftpy.registry.SlotRegistry.activeProjectIds()) {
            if (net.xmilon.craftpy.project.ModuleLoader.findFileById(projectId) != null) {
                // a module: it loaded above when this world has it on; otherwise its content is detached
                if (!net.xmilon.craftpy.project.ModuleLoader.isLoaded(projectId)) {
                    net.xmilon.craftpy.registry.SlotRegistry.releaseProject(projectId);
                }
                continue;
            }
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
        net.xmilon.craftpy.project.ModuleLoader.useGlobalState();
    }

    /** Again once the world is gone: a worker a late event started while stopping is stopped too. */
    @SubscribeEvent
    public void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        net.xmilon.craftpy.content.MachineState.resetNotifications();
        net.xmilon.craftpy.python.PyRuntime.shutdown();
    }

    @SubscribeEvent
    public void onRegisterCommands(net.neoforged.neoforge.event.RegisterCommandsEvent event) {
        CraftPyCommand.register(event.getDispatcher(), event.getBuildContext());
    }
}
