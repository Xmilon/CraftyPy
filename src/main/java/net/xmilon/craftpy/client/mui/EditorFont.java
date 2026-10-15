package net.xmilon.craftpy.client.mui;

import java.io.InputStream;

import icyllis.modernui.graphics.text.FontFamily;
import icyllis.modernui.text.Typeface;
import net.xmilon.craftpy.CraftPyLog;

/**
 * The font of the code editor, console and completion list: JetBrains Mono, shipped in the jar
 * ({@code assets/craftpy/font/jetbrains_mono.ttf}, SIL Open Font License). Only these views use it; the rest of the
 * IDE keeps Modern UI's own font. If the file cannot be read or parsed the editor falls back to the system monospace
 * font, so a broken font never stops the editor from opening.
 */
final class EditorFont {
    private static final String RESOURCE = "/assets/craftpy/font/jetbrains_mono.ttf";
    private static Typeface typeface;
    private static boolean tried;

    private EditorFont() {
    }

    static synchronized Typeface get() {
        if (!tried) {
            tried = true;
            try (InputStream in = EditorFont.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    throw new java.io.FileNotFoundException(RESOURCE);
                }
                // not registered globally (false): only views that ask for it get the font; glyphs it lacks come from the system monospace font
                FontFamily family = FontFamily.createFamily(in, false);
                typeface = Typeface.createTypeface(family, FontFamily.MONOSPACED);
            } catch (Exception | LinkageError e) {
                CraftPyLog.LOGGER.warn("Editor font JetBrains Mono not available, using the system monospace font: {}", e.toString());
            }
        }
        return typeface != null ? typeface : Typeface.MONOSPACED;
    }
}
