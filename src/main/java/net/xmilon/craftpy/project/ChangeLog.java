package net.xmilon.craftpy.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.xmilon.craftpy.CraftPy;

/** One line per saved, deleted, renamed or uploaded file of a project, kept in {@code system/logs/changes-<id>.log}. */
public final class ChangeLog {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int KEEP = 2000;

    private ChangeLog() {
    }

    private static Path file(String id) {
        return PyForgePaths.logs().resolve("changes-" + id + ".log");
    }

    public static synchronized void record(String id, String message) {
        if (!ProjectManager.isValidId(id)) {
            return;
        }
        try {
            Path file = file(id);
            Files.createDirectories(file.getParent());
            Files.writeString(file, LocalDateTime.now().format(TIME) + "  " + message.replace('\n', ' ') + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (Files.size(file) > 256 * 1024) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                Files.write(file, lines.subList(Math.max(0, lines.size() - KEEP), lines.size()), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            CraftPy.LOGGER.warn("Could not write the change log of {}: {}", id, e.getMessage());
        }
    }

    /** The newest {@code limit} entries, newest first. */
    public static synchronized List<String> read(String id, int limit) {
        if (!ProjectManager.isValidId(id) || !Files.exists(file(id))) {
            return List.of();
        }
        try {
            List<String> lines = new ArrayList<>(Files.readAllLines(file(id), StandardCharsets.UTF_8));
            Collections.reverse(lines);
            return lines.subList(0, Math.min(limit, lines.size()));
        } catch (IOException e) {
            return List.of();
        }
    }
}
