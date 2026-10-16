package net.xmilon.craftpy.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;
import net.xmilon.craftpy.network.ProjectAction;
import net.xmilon.craftpy.network.RequestPayload;

/** {@link IdeHost} inside the running game. */
public final class McHost implements IdeHost {

    @Override
    public void execute(Runnable task) {
        Minecraft.getInstance().execute(task);
    }

    @Override
    public void send(ProjectAction action, String projectId, String data) {
        if (data != null && !net.xmilon.craftpy.network.PayloadText.fits(data, RequestPayload.MAX_DATA_BYTES)) {
            // encoding it would throw and disconnect: answer locally instead
            net.xmilon.craftpy.client.ClientProjectStore.getInstance().onResponse(
                    net.xmilon.craftpy.network.ResponsePayload.failure(action, "Too big to send to the server ("
                            + data.length() / 1024 + " KiB)"));
            return;
        }
        PacketDistributor.sendToServer(RequestPayload.of(action, projectId, data));
    }

    @Override
    public String clipboard() {
        return Minecraft.getInstance().keyboardHandler.getClipboard();
    }

    @Override
    public void setClipboard(String text) {
        Minecraft.getInstance().keyboardHandler.setClipboard(text);
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public InputStream doc(String file) throws IOException {
        var resource = Minecraft.getInstance().getResourceManager().getResource(ResourceLocation.fromNamespaceAndPath("craftpy", "docs/" + file));
        return resource.isPresent() ? resource.get().open() : null;
    }

    @Override
    public boolean inGame() {
        return true;
    }

    @Override
    public void closeScreen() {
        var screen = Minecraft.getInstance().screen;
        if (screen != null) {
            screen.onClose();
        }
    }
}
