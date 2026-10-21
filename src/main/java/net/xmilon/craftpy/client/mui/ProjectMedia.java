package net.xmilon.craftpy.client.mui;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import net.xmilon.craftpy.client.ClientProjectStore;
import net.xmilon.craftpy.client.IdeHost;
import net.xmilon.craftpy.client.media.MediaException;
import net.xmilon.craftpy.client.media.MediaSource;
import net.xmilon.craftpy.client.media.MediaStore;

/**
 * Reads the media of the project that is open in the editor straight from its folder (through the server, as for texture
 * previews), so the UI maker preview shows a file the moment it is added, before the project is loaded into the game and
 * its resource pack is rebuilt. Files of other modules come from the loaded game packs.
 */
final class ProjectMedia {
    private ProjectMedia() {
    }

    static MediaStore.Reader reader() {
        MediaStore.Reader game = MediaStore.gameReader();
        return new MediaStore.Reader() {
            @Override
            public byte[] read(MediaSource source) throws MediaException {
                String project = ClientProjectStore.getInstance().activeProjectId();
                if (project == null || !project.equals(source.owner())) {
                    return game.read(source);
                }
                CompletableFuture<ClientProjectStore.AssetBytes> answer = new CompletableFuture<>();
                IdeHost.get().execute(() -> ClientProjectStore.getInstance().requestAsset(project, "assets/" + source.path(),
                        answer::complete));
                try {
                    ClientProjectStore.AssetBytes bytes = answer.get(20, TimeUnit.SECONDS);
                    if (bytes.error() != null && !bytes.error().isEmpty()) {
                        throw new MediaException("assets/" + source.path() + " of the project: " + bytes.error());
                    }
                    return bytes.data();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new MediaException("Interrupted while reading " + source.id(), e);
                } catch (ExecutionException | TimeoutException e) {
                    throw new MediaException("Could not read assets/" + source.path() + " of the project: " + e.getMessage(), e);
                }
            }

            @Override
            public String key() {
                return "project:" + ClientProjectStore.getInstance().activeProjectId();
            }
        };
    }
}
