package net.xmilon.craftpy.catalog;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import net.xmilon.craftpy.project.PyForgePaths;

/**
 * The IDE's window onto what the installed mods offer: saved catalogs ({@link CatalogStore}) of the mods that are
 * installed now, plus the shipped seeds, merged into a {@link CatalogView}. Which mods are installed is asked each time the
 * view is rebuilt, so removing a mod hides its entries at the next {@link #invalidate()} (a dump, a restart) without
 * deleting anything.
 */
public final class ModCatalog {
    private static ModCatalog instance;

    private final CatalogStore store;
    private final Map<String, Catalog> seeds;
    private volatile Supplier<Set<String>> installed;
    private volatile CatalogView view;

    public ModCatalog(CatalogStore store, Supplier<Set<String>> installed, Map<String, Catalog> seeds) {
        this.store = store;
        this.installed = installed != null ? installed : store::knownMods; // nobody said which mods: everything saved counts
        this.seeds = seeds;
    }

    public static synchronized ModCatalog get() {
        if (instance == null) {
            Path dir = PyForgePaths.system().resolve("catalog");
            instance = new ModCatalog(new CatalogStore(dir), null, CatalogSeeds.load());
        }
        return instance;
    }

    public CatalogStore store() {
        return store;
    }

    /** Sets where the installed mods come from (the game's mod list, or a scanned mods folder). */
    public void setInstalled(Supplier<Set<String>> installed) {
        this.installed = installed;
        invalidate();
    }

    public void invalidate() {
        view = null;
    }

    public CatalogView view() {
        CatalogView current = view;
        if (current == null) {
            Set<String> mods = installed.get();
            current = CatalogView.of(store.visible(mods), seeds, mods);
            view = current;
        }
        return current;
    }

}
