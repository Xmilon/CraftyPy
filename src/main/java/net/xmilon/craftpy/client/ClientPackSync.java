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
                if (current != null && minecraft.screen != current) {
                    minecraft.setScreen(current);
                }
            }));
        });
    }
}
