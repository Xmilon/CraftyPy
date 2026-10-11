package net.xmilon.craftpy.assets;

/**
 * The kinds of asset a reference in the code editor can point at.
 * Kept free of Minecraft types so the parser is unit-testable.
 */
public enum AssetKind {
    TEXTURE("texture"),
    MODEL("model"),
    SOUND("sound"),
    ITEM("item"),
    BLOCK("block");

    private final String label;

    AssetKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}