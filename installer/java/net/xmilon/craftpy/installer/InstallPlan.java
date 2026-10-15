package net.xmilon.craftpy.installer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Decides, from what is already installed, the steps needed. Pure: nothing here touches the disk or the network. */
public final class InstallPlan {

    private InstallPlan() {
    }

    /** {@code installPython}: the user agreed to run the package manager (Python and the optional pip package). */
    public static List<Step> build(Env env, Requirements req, Path workDir, boolean installPython) {
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
        if (env.pythonCommand() == null) {
            steps.add(pythonStep(env, req, installPython));
        } else if (installPython) {
            steps.add(new Step.Run("Optional: OGG export for the sound editor",
                    List.of(env.pythonCommand(), "-m", "pip", "install", "--user", req.pipOptional())));
        }
        return steps;
    }

    /** Installing Python changes the whole PC, so it is only a command when the user asked for it. */
    private static Step pythonStep(Env env, Requirements req, boolean install) {
        List<String> command = pythonCommand(env.packageManager(), req);
        if (command == null) {
            return new Step.Manual("Python " + req.pythonMin() + " or newer is missing: install it from https://www.python.org/downloads/"
                    + " or set pythonPath in CraftPy's config.");
        }
        if (!install) {
            return new Step.Manual("Python " + req.pythonMin() + " or newer is missing. Run again with --install-python to run: "
                    + String.join(" ", command));
        }
        return new Step.Run("Install Python " + req.pythonMin(), command);
    }

    static List<String> pythonCommand(String packageManager, Requirements req) {
        if (packageManager == null) {
            return null;
        }
        return switch (packageManager) {
            case "winget" -> List.of("winget", "install", "-e", "--id", req.pythonWinget());
            case "brew" -> List.of("brew", "install", req.pythonBrew());
            case "apt" -> List.of("sudo", "apt", "install", "-y", req.pythonApt());
            case "dnf" -> List.of("sudo", "dnf", "install", "-y", req.pythonDnf());
            case "pacman" -> List.of("sudo", "pacman", "-S", "--noconfirm", req.pythonPacman());
            default -> null;
        };
    }
}
