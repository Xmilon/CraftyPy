package net.xmilon.craftpy.client.mui;

import icyllis.modernui.view.View;

/** One tab of the asset dock (resource preview, texture editor, sound editor). UI thread only. */
interface DockPanel {
    View view();

    /** True when replacing or discarding the panel would lose work. */
    boolean dirty();

    /** Esc: true when the panel used the key (cancelled a paste, cleared a selection...). */
    boolean back();
}
