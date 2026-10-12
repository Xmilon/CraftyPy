package net.xmilon.craftpy.client.ui;

/**
 * Global UI state and management.
 */
public final class UIContext {
    private Widget focus;
    private Widget root;

    public void setRoot(Widget root) {
        this.root = root;
    }

    public Widget root() {
        return root;
    }

    public void setFocus(Widget widget) {
        if (this.focus != widget) {
            // TODO: call blur/focus events
            this.focus = widget;
        }
    }

    public Widget focus() {
        return focus;
    }
}
