package net.xmilon.craftpy.ide;

import java.io.InputStream;
import java.nio.file.Path;

import icyllis.modernui.ModernUI;
import icyllis.modernui.core.Clipboard;
import icyllis.modernui.core.Core;
import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.client.ClientProjectStore;
import net.xmilon.craftpy.client.IdeHost;
import net.xmilon.craftpy.network.ProjectAction;
import net.xmilon.craftpy.network.RequestPayload;
import net.xmilon.craftpy.network.ResponsePayload;
import net.xmilon.craftpy.server.ProjectRequestHandler;

/**
 * {@link IdeHost} of the standalone IDE: requests go straight to the project service in this JVM (as
 * {@link ProjectRequestHandler.Caller#LOCAL}), the store lives on the Modern UI thread, the clipboard is the system one.
 */
final class LocalHost implements IdeHost {

    @Override
    public void execute(Runnable task) {
        Core.executeOnUiThread(task);
    }

    @Override
    public void send(ProjectAction action, String projectId, String data) {
        RequestPayload request = RequestPayload.of(action, projectId, data);
        ProjectRequestHandler.handle(ProjectRequestHandler.Caller.LOCAL, request)
                .exceptionally(error -> {
                    CraftPyLog.LOGGER.error("Request {} failed", action, error);
                    return new ResponsePayload(action, false, "Internal error: " + error.getMessage(), "");
                })
                .thenAccept(response -> {
                    if (response != null) { // null = an upload chunk that needs no reply
                        execute(() -> ClientProjectStore.getInstance().onResponse(response));
                    }
                });
    }

    @Override
    public String clipboard() {
        String text = Clipboard.getText();
        return text == null ? "" : text;
    }

    @Override
    public void setClipboard(String text) {
        Clipboard.setText(text == null ? "" : text);
    }

    @Override
    public Path configDir() {
        return IdeMain.home().resolve("config");
    }

    @Override
    public InputStream doc(String file) {
        return LocalHost.class.getResourceAsStream("/assets/craftpy/docs/" + file);
    }

    @Override
    public void closeScreen() {
        ModernUI app = ModernUI.getInstance();
        if (app != null && app.getWindow() != null) {
            app.getWindow().setShouldClose(true);
        }
    }
}
