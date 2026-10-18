package net.xmilon.craftpy.installer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Decides, from what is already installed, the steps needed. Pure: nothing here touches the disk or the network. */
public final class InstallPlan {

    private InstallPlan() {
    }

    /** The plan with CPython installed (the mod jar ships it; see the mod's {@code BundledPython}). */
    public static List<Step> build(Env env, Requirements req, Path workDir) {
        return build(env, req, workDir, true);
    }

    /** {@code bundledPython}: the player's answer to "install CPython?". */
    public static List<Step> build(Env env, Requirements req, Path workDir, boolean bundledPython) {
        List<Step> steps = new ArrayList<>();
        if (env.javaMajor() < req.javaMin()) {
            steps.add(new Step.Manual("Java " + req.javaMin() + " or newer is needed to run the game (found " + env.javaMajor()
                    + "). Install it from https://adoptium.net and run the installer again."));
        }
        if (!env.neoforgeInstalled()) {
            Path installer = workDir.resolve("neoforge-" + req.neoforgeVersion() + "-installer.jar");
            steps.add(new Step.Download("NeoForge " + req.neoforgeVersion(), req.neoforgeUrl(), installer, req.neoforgeSha256()));
            steps.add(new Step.Run("Install NeoForge (start the Minecraft launcher once first so it has a profile)",
                    List.of("java", "-jar", installer.toString(), "--installClient", env.minecraftDir().toString())));
        }
        Path mods = env.minecraftDir().resolve("mods");
        if (!env.modFiles().contains(req.modernUiFile())) {
            steps.add(new Step.Download("Modern UI", req.modernUiUrl(), mods.resolve(req.modernUiFile()), req.modernUiSha256()));
        }
        steps.add(new Step.CopyMod(mods.resolve("craftpy.jar")));
        steps.add(new Step.PythonChoice(pythonMarker(env.minecraftDir()), bundledPython));
        return steps;
    }

    /** The file that tells the mod not to unpack its own Python (the mod's {@code BundledPython.OPT_OUT}). */
    public static Path pythonMarker(Path minecraftDir) {
        return minecraftDir.resolve("pyforge").resolve("system").resolve("no_bundled_python");
    }
}
