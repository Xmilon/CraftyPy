package net.xmilon.craftpy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class FileTreeTest {
    private static final List<String> FILES = List.of(
            "project.json", "code/main.py", "code/blocks/ruby.py", "assets/textures/a.png", "data/empty/");

    @Test
    void collapsedShowsOnlyTopLevelFoldersFirst() {
        var rows = FileTree.visible(FILES, Set.of());
        assertEquals(List.of("assets", "code", "data", "project.json"), rows.stream().map(FileTree.Row::path).toList());
        assertTrue(rows.get(0).folder());
        assertEquals(false, rows.get(0).expanded());
    }

    @Test
    void expandedShowsChildrenIndentedAndFoldersBeforeFiles() {
        var rows = FileTree.visible(FILES, Set.of("code"));
        assertEquals(List.of("assets", "code", "code/blocks", "code/main.py", "data", "project.json"),
                rows.stream().map(FileTree.Row::path).toList());
        assertEquals(1, rows.get(2).depth());
        assertEquals("code/", rows.get(3).prefix().substring(0, 5));
    }

    @Test
    void emptyFoldersAreFolders() {
        var rows = FileTree.visible(FILES, Set.of("data"));
        FileTree.Row empty = rows.stream().filter(r -> r.path().equals("data/empty")).findFirst().orElseThrow();
        assertTrue(empty.folder());
        assertEquals("data/empty/", empty.prefix());
    }

    @Test
    void ancestorsAndFolders() {
        assertEquals(List.of("a", "a/b"), FileTree.ancestors("a/b/c.py"));
        assertEquals(List.of(), FileTree.ancestors("c.py"));
        assertEquals(List.of("assets", "assets/textures", "code", "code/blocks", "data", "data/empty"),
                FileTree.folders(FILES));
    }
}
