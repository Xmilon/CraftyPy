package net.xmilon.craftpy.installer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallPlanTest {

    private static final Requirements REQ = Requirements.load();
    private static final Path MC = Path.of("mc");
    private static final Path WORK = Path.of("work");

    private static Env env(int java, boolean neoforge, Set<String> mods) {
        return new Env("linux", MC, java, neoforge, mods);
    }

    @Test
    void freshPcGetsNeoForgeModernUiAndCraftPyInOrder() {
        List<Step> steps = InstallPlan.build(env(21, false, Set.of()), REQ, WORK);
        assertEquals(4, steps.size(), steps.toString());
        assertTrue(steps.get(0) instanceof Step.Download d && d.url().contains("neoforge-21.1.259-installer.jar"));
        assertTrue(steps.get(1) instanceof Step.Run r && r.command().containsAll(List.of("--installClient", MC.toString())));
        assertTrue(steps.get(2) instanceof Step.Download d && d.target().equals(MC.resolve("mods").resolve(REQ.modernUiFile())));
        assertTrue(steps.get(3) instanceof Step.CopyMod);
    }

    @Test
    void existingPartsAreSkipped() {
        List<Step> steps = InstallPlan.build(env(21, true, Set.of(REQ.modernUiFile())), REQ, WORK);
        assertEquals(List.of(new Step.CopyMod(MC.resolve("mods").resolve("craftpy.jar"))), steps);
    }

    @Test
    void oldJavaIsReportedNotInstalled() {
        List<Step> steps = InstallPlan.build(env(17, true, Set.of(REQ.modernUiFile())), REQ, WORK);
        assertTrue(steps.get(0) instanceof Step.Manual m && m.text().contains("Java 21"));
    }


    @Test
    void minecraftFolderPerSystem() {
        assertEquals(Path.of("C:\\Users\\a\\AppData\\Roaming", ".minecraft"), Env.defaultMinecraftDir("windows", "C:\\Users\\a", "C:\\Users\\a\\AppData\\Roaming"));
        assertEquals(Path.of("/h", "Library", "Application Support", "minecraft"), Env.defaultMinecraftDir("macos", "/h", null));
        assertEquals(Path.of("/h", ".minecraft"), Env.defaultMinecraftDir("linux", "/h", null));
        assertEquals("macos", Env.osName("Mac OS X"));
        assertEquals("windows", Env.osName("Windows 11"));
    }

    @Test
    void sha256MatchesAKnownValue(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("a.txt"), "abc");
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Installer.sha256(file));
    }

    @Test
    void shippedRequirementsHaveFullSha256Hashes() {
        assertEquals(64, REQ.neoforgeSha256().length());
        assertEquals(64, REQ.modernUiSha256().length());
        assertTrue(REQ.neoforgeUrl().startsWith("https://") && REQ.modernUiUrl().startsWith("https://"));
    }

    @Test
    void downloadsAreKeptOnlyWhenTheHashMatches(@TempDir Path dir) throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a", exchange -> {
            exchange.sendResponseHeaders(200, 3);
            exchange.getResponseBody().write("abc".getBytes());
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/a";
            String good = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
            Path bad = dir.resolve("bad.jar");
            assertTrue(!new Installer(null, x -> { }).run(List.of(new Step.Download("x", url, bad, "00".repeat(32)))));
            assertTrue(!Files.exists(bad) && !Files.exists(dir.resolve("bad.jar.part")));
            Path ok = dir.resolve("ok.jar");
            assertTrue(new Installer(null, x -> { }).run(List.of(new Step.Download("x", url, ok, good))));
            assertEquals("abc", Files.readString(ok));
        } finally {
            server.stop(0);
        }
    }
}
