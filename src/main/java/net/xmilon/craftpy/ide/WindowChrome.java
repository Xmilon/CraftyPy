package net.xmilon.craftpy.ide;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.client.mui.IdeColors;

/**
 * The native frame of the standalone window: its title, and on Windows a dark caption in the IDE's panel color (DWM
 * attributes; Windows 10 only knows the dark mode flag, Windows 11 also takes the colors). Anything that fails is skipped:
 * the frame then simply stays the system one.
 */
final class WindowChrome {
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    private static final int DWMWA_BORDER_COLOR = 34;
    private static final int DWMWA_CAPTION_COLOR = 35;
    private static final int DWMWA_TEXT_COLOR = 36;

    private WindowChrome() {
    }

    interface Dwm extends Library {
        int DwmSetWindowAttribute(Pointer hwnd, int attribute, IntByReference value, int size);
    }

    /** Called on the main thread right after Modern UI created the window. */
    static void apply(long window, String title) {
        GLFW.glfwSetWindowTitle(window, title);
        setIcon();
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            return;
        }
        try {
            Pointer hwnd = new Pointer(GLFWNativeWin32.glfwGetWin32Window(window));
            Dwm dwm = Native.load("dwmapi", Dwm.class);
            dwm.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, new IntByReference(1), 4);
            dwm.DwmSetWindowAttribute(hwnd, DWMWA_CAPTION_COLOR, new IntByReference(colorRef(IdeColors.PANEL)), 4);
            dwm.DwmSetWindowAttribute(hwnd, DWMWA_BORDER_COLOR, new IntByReference(colorRef(IdeColors.SEPARATOR)), 4);
            dwm.DwmSetWindowAttribute(hwnd, DWMWA_TEXT_COLOR, new IntByReference(colorRef(IdeColors.TEXT)), 4);
        } catch (Throwable e) { // older Windows, no JNA: the light system frame stays
            CraftPyLog.LOGGER.debug("Dark window frame not available: {}", e.toString());
        }
    }

    /** The CraftPy logo in the title bar and task bar (several sizes, the system picks one). */
    private static void setIcon() {
        var app = icyllis.modernui.ModernUI.getInstance();
        if (app == null || app.getWindow() == null) {
            return;
        }
        java.util.List<icyllis.modernui.graphics.Bitmap> icons = new java.util.ArrayList<>();
        try {
            for (int size : new int[]{16, 32, 48, 64, 128}) {
                try (var in = WindowChrome.class.getResourceAsStream("/assets/craftpy/icon/icon_" + size + ".png")) {
                    if (in != null) {
                        icons.add(icyllis.modernui.graphics.BitmapFactory.decodeStream(in));
                    }
                }
            }
            if (!icons.isEmpty()) {
                app.getWindow().setIcon(icons.toArray(new icyllis.modernui.graphics.Bitmap[0]));
            }
        } catch (Exception e) {
            CraftPyLog.LOGGER.debug("Window icon not set: {}", e.toString());
        } finally {
            icons.forEach(icyllis.modernui.graphics.Bitmap::close);
        }
    }

    /** ARGB to a Win32 COLORREF (0x00BBGGRR). */
    static int colorRef(int argb) {
        return (argb & 0xFF) << 16 | (argb & 0xFF00) | (argb >> 16 & 0xFF);
    }
}
