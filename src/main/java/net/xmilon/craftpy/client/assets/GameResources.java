package net.xmilon.craftpy.client.assets;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.client.IdeHost;

/**
 * The resources the asset browser, previews and texture editor read. In the game: the game's resource manager. In the
 * standalone IDE: a resource manager of the same kind built over the vanilla assets jar on the classpath and CraftPy's
 * generated pack, so the browser shows the same vanilla textures, models and sounds without the game running.
 */
public final class GameResources {
    private static volatile ResourceManager standalone;
    private static volatile boolean standaloneTried;

    private GameResources() {
    }

    /** True when the game runs (sounds can play, the 3D screens open). */
    public static boolean inGame() {
        return IdeHost.get().inGame();
    }

    /** The resource manager to read from, or null when there is none. */
    public static ResourceManager manager() {
        if (inGame()) {
            Minecraft minecraft = Minecraft.getInstance();
            return minecraft == null ? null : minecraft.getResourceManager();
        }
        if (!standaloneTried) {
            synchronized (GameResources.class) {
                if (!standaloneTried) {
                    standalone = buildStandalone();
                    standaloneTried = true;
                }
            }
        }
        return standalone;
    }

    private static ResourceManager buildStandalone() {
        List<PackResources> packs = new ArrayList<>();
        try {
            var url = GameResources.class.getClassLoader().getResource("assets/minecraft/textures/block/stone.png");
            if (url != null && "jar".equals(url.getProtocol())) {
                String spec = url.toString();
                URI jar = URI.create(spec.substring(0, spec.indexOf("!/")));
                FileSystem fs;
                try {
                    fs = FileSystems.newFileSystem(jar, Map.of());
                } catch (java.nio.file.FileSystemAlreadyExistsException e) {
                    fs = FileSystems.getFileSystem(jar);
                }
                packs.add(pack("vanilla", fs.getPath("/")));
            }
            Path craftpy = net.xmilon.craftpy.dynamics.PackBuilder.resourcePackRoot();
            if (Files.isDirectory(craftpy)) {
                packs.add(pack("craftpy_dynamic", craftpy));
            }
            if (packs.isEmpty()) {
                CraftPyLog.LOGGER.warn("No vanilla assets on the classpath: the asset browser stays empty");
                return null;
            }
            return new MultiPackResourceManager(PackType.CLIENT_RESOURCES, packs);
        } catch (Exception | LinkageError e) {
            CraftPyLog.LOGGER.warn("Could not open the vanilla assets for the standalone IDE: {}", e.toString());
            return null;
        }
    }

    private static PackResources pack(String id, Path root) {
        return new PathPackResources(new PackLocationInfo(id, Component.literal(id), PackSource.BUILT_IN, Optional.empty()), root);
    }
}
