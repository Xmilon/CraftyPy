package net.xmilon.craftpy.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Client-side resource pack refresh. Called when the server reports a change to
 * the active project set so that generated block/item models and textures become
 * visible without any manual action. Client-only class.
 */
public final class ClientPackSync {
    private ClientPackSync() {
    }

    /**
     * New or changed CraftPy content: rebuild the creative tabs (that is where JEI / EMI / REI read items from) and send the
     * same "recipes and tags arrived" events the game sends when it joins a server, so those mods rebuild their lists
     * and recipe views. Every listener is built to receive these again on each sync, so other mods are not disturbed.
     */
    static void announceContent(Minecraft minecraft) {
        var connection = minecraft.getConnection();
        if (connection == null || minecraft.player == null) {
            return;
        }
        try {
            var parameters = new net.minecraft.world.item.CreativeModeTab.ItemDisplayParameters(
                    connection.enabledFeatures(), minecraft.player.canUseGameMasterBlocks(), connection.registryAccess());
            net.minecraft.world.item.CreativeModeTab search = null;
            for (var tab : net.minecraft.world.item.CreativeModeTabs.allTabs()) {
                if (tab.getType() == net.minecraft.world.item.CreativeModeTab.Type.SEARCH) {
                    search = tab;
                } else {
                    tab.buildContents(parameters);
                }
            }
            if (search != null) {
                search.buildContents(parameters); // after the others: it collects their items
            }
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.TagsUpdatedEvent(
                    connection.registryAccess(), true, minecraft.isLocalServer()));
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                    new net.neoforged.neoforge.client.event.RecipesUpdatedEvent(connection.getRecipeManager()));
        } catch (RuntimeException e) {
            net.xmilon.craftpy.CraftPyLog.LOGGER.warn("Could not announce new CraftPy content to item-list mods: {}", e.toString());
        }
    }

    public static void reload() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        // Re-reads repository sources (our dynamic pack folder) and reloads assets.
        // Vanilla installs a loading overlay while this runs; make sure it happens on
        // the render thread and that the IDE is put back afterwards so the overlay
        // never lingers on top of the editor.
        minecraft.execute(() -> {
            Screen current = minecraft.screen;
            minecraft.reloadResourcePacks().whenComplete((ignored, error) -> minecraft.execute(() -> {
                if (error != null) {
                    return;
                }
                net.xmilon.craftpy.client.assets.AssetPreview.invalidate();
                announceContent(minecraft);
                if (current != null && minecraft.screen != current) {
                    minecraft.setScreen(current);
                }
            }));
        });
    }
}
