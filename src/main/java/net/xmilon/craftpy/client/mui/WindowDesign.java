package net.xmilon.craftpy.client.mui;

import icyllis.modernui.view.View;

/** A designer shown in the editor's Design tab: the Modern UI window maker or the Minecraft-style one. */
interface WindowDesign {
    View view();

    /** Esc: cancels a gesture or a chosen tool, or clears the selection; false when there was nothing to undo. */
    boolean back();
}
