package net.xmilon.craftpy.installer;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Consumer;

/** Runs the {@link Step}s of a plan. Downloads are checked against their SHA-256 before they replace anything. */
public final class Installer {

    private final Path craftpyJar;
    private final Consumer<String> log;

    /** {@code craftpyJar}: the jar to copy, or null to use the one bundled inside the installer. */
    public Installer(Path craftpyJar, Consumer<String> log) {
        this.craftpyJar = craftpyJar;
        this.log = log;
    }

    /** Returns true when every step succeeded and nothing needs doing by hand. */
    public boolean run(java.util.List<Step> steps) {
        boolean manual = false;
        for (Step step : steps) {
            log.accept(step.text());
            try {
                switch (step) {
                    case Step.Download d -> download(d);
                    case Step.Run r -> execute(r);
                    case Step.CopyMod c -> copyMod(c);
                    case Step.PythonChoice p -> pythonChoice(p);
                    case Step.Manual m -> manual = true;
                }
            } catch (IOException | RuntimeException e) {
                log.accept("FAILED: " + e.getMessage());
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !manual;
    }

    private void download(Step.Download step) throws IOException, InterruptedException {
        if (Files.isRegularFile(step.target()) && sha256(step.target()).equalsIgnoreCase(step.sha256())) {
            log.accept("  already there");
            return;
        }
        Files.createDirectories(step.target().toAbsolutePath().getParent());
        Path temp = step.target().resolveSibling(step.target().getFileName() + ".part");
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<Path> response = client.send(HttpRequest.newBuilder(URI.create(step.url())).build(),
                HttpResponse.BodyHandlers.ofFile(temp));
        if (response.statusCode() != 200) {
            Files.deleteIfExists(temp);
            throw new IOException("download of " + step.url() + " answered " + response.statusCode());
        }
        String actual = sha256(temp);
        if (!actual.equalsIgnoreCase(step.sha256())) {
            Files.deleteIfExists(temp);
            throw new IOException("checksum of " + step.what() + " does not match (got " + actual + "); nothing was installed");
        }
        Files.move(temp, step.target(), StandardCopyOption.REPLACE_EXISTING);
    }

    private void execute(Step.Run step) throws IOException, InterruptedException {
        int code = new ProcessBuilder(step.command()).inheritIO().start().waitFor();
        if (code != 0) {
            throw new IOException(step.what() + " exited with " + code);
        }
    }

    private void copyMod(Step.CopyMod step) throws IOException {
        Files.createDirectories(step.target().getParent());
        if (craftpyJar != null) {
            Files.copy(craftpyJar, step.target(), StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        try (InputStream in = Installer.class.getResourceAsStream("/craftpy.jar")) {
            if (in == null) {
                throw new IOException("this installer has no CraftPy jar inside; pass --craftpy <jar>");
            }
            Files.copy(in, step.target(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void pythonChoice(Step.PythonChoice step) throws IOException {
        if (step.bundled()) {
            Files.deleteIfExists(step.marker());
            return;
        }
        Files.createDirectories(step.marker().getParent());
        Files.writeString(step.marker(), "The player chose not to install CPython with CraftPy. Delete this file to let"
                + " CraftPy unpack its own Python on the next start.\n");
    }

    public static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
