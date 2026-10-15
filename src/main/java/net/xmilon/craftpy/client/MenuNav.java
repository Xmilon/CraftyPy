package net.xmilon.craftpy.client;

/** Which row an arrow key moves a menu's highlight to. Pure, so the wrap-around and the skipped rows are tested. */
public final class MenuNav {
    private MenuNav() {
    }

    /**
     * The next usable row after {@code from} in direction {@code dir} (+1 down, -1 up), wrapping around. {@code from < 0}
     * means nothing is highlighted yet: down finds the first row, up the last. -1 when no row is usable.
     */
    public static int step(boolean[] usable, int from, int dir) {
        int count = usable.length;
        int index = from >= 0 ? from : dir > 0 ? -1 : count;
        for (int i = 0; i < count; i++) {
            index = ((index + dir) % count + count) % count;
            if (usable[index]) {
                return index;
            }
        }
        return -1;
    }
}
