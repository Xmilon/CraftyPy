package net.xmilon.craftpy.client.mui;

import com.google.gson.JsonObject;

import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.Canvas;
import icyllis.modernui.graphics.Paint;
import icyllis.modernui.view.View;
import net.xmilon.craftpy.menu.LegacyShapes;
import net.xmilon.craftpy.menu.ScreenLayout;

/**
 * One drawn piece of a Minecraft-style window in the UI maker's preview: panel, slot, player inventory, bars, arrow and flame.
 * It paints with the same {@link LegacyShapes} as the game's machine screen, one GUI unit per dp, with sample fill levels so
 * the bars show something. Labels and icons are ordinary widgets (see {@code WindowFragment}).
 */
final class LegacyView extends View {
    /** How full the sample bars, arrows and flames look in the preview. */
    private static final double SAMPLE = 0.6;

    private final ScreenLayout.Piece piece;
    private final Paint paint = new Paint();

    LegacyView(Context context, JsonObject node) {
        super(context);
        this.piece = ScreenLayout.pieceOf(node, 256, 256);
    }

    /** Slots and the player inventory have a one unit frame around their items. */
    static boolean framed(String type) {
        return type.equals("lg_slot") || type.equals("lg_player");
    }

    private int frame() {
        return framed(piece.type()) ? 1 : 0;
    }

    private int unit() {
        return Math.max(1, dp(1));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int extra = 2 * frame() * unit();
        setMeasuredDimension(piece.width() * unit() + extra, piece.height() * unit() + extra);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        int u = unit(), f = frame();
        LegacyShapes.Surface surface = (x1, y1, x2, y2, argb) -> {
            paint.setColor(argb);
            canvas.drawRect((x1 + f) * u, (y1 + f) * u, (x2 + f) * u, (y2 + f) * u, paint);
        };
        switch (piece.type()) {
            case "lg_panel" -> LegacyShapes.panel(surface, 0, 0, piece.width(), piece.height());
            case "lg_slot" -> LegacyShapes.slotBox(surface, 0, 0);
            case "lg_player" -> LegacyShapes.playerSlots(surface, 0, 0);
            case "lg_energy" -> LegacyShapes.energy(surface, 0, 0, piece.width(), piece.height(), SAMPLE);
            case "lg_fluid" -> {
                LegacyShapes.well(surface, 0, 0, piece.width(), piece.height());
                int filled = (int) Math.round(piece.height() * SAMPLE);
                surface.fill(0, piece.height() - filled, piece.width(), piece.height(), 0xFF3F76E4);
            }
            case "lg_arrow" -> LegacyShapes.arrow(surface, 0, 0, piece.width(), piece.height(), SAMPLE);
            case "lg_flame" -> LegacyShapes.flame(surface, 0, 0, piece.width(), piece.height(), SAMPLE);
            default -> {
            }
        }
    }
}
