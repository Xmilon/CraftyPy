package net.xmilon.craftpy.installer;

import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/**
 * Entry point. {@code --cli} (or no display) asks in the terminal; otherwise a small window shows the plan first.
 * Flags: {@code --dry-run} print the plan only, {@code --yes} no question, {@code --install-python} also run the package
 * manager for Python and the optional pip package, {@code --dir <minecraft folder>}, {@code --craftpy <jar>}.
 */
public final class InstallerMain {

    private InstallerMain() {
    }

    public static void main(String[] args) throws Exception {
        List<String> a = List.of(args);
        Requirements req = Requirements.load();
        String os = Env.osName(System.getProperty("os.name"));
        Path dir = a.contains("--dir") ? Path.of(a.get(a.indexOf("--dir") + 1))
                : Env.defaultMinecraftDir(os, System.getProperty("user.home"), System.getenv("APPDATA"));
        Path jar = a.contains("--craftpy") ? Path.of(a.get(a.indexOf("--craftpy") + 1)) : null;
        boolean python = a.contains("--install-python");
        Path work = Path.of(System.getProperty("java.io.tmpdir"), "craftpy-installer");
        if (a.contains("--cli") || GraphicsEnvironment.isHeadless()) {
            cli(a, dir, jar, req, python, work);
        } else {
            SwingUtilities.invokeLater(() -> window(dir, jar, req, work));
        }
    }

    private static void cli(List<String> a, Path dir, Path jar, Requirements req, boolean python, Path work) {
        List<Step> steps = InstallPlan.build(Detector.detect(dir, req), req, work, python);
        System.out.println("CraftPy installer, Minecraft folder: " + dir);
        steps.forEach(s -> System.out.println(" - " + s.text()));
        if (a.contains("--dry-run")) {
            return;
        }
        if (!a.contains("--yes")) {
            System.out.print("Run these steps? [y/N] ");
            if (!new Scanner(System.in).nextLine().trim().equalsIgnoreCase("y")) {
                return;
            }
        }
        boolean done = new Installer(jar, System.out::println).run(steps);
        System.out.println(done ? "Done. Start the NeoForge " + req.neoforgeVersion() + " profile." : "Finished with things left to do (see above).");
    }

    private static void window(Path dir, Path jar, Requirements req, Path work) {
        JFrame frame = new JFrame("CraftPy installer");
        JTextArea text = new JTextArea(18, 80);
        text.setEditable(false);
        JCheckBox python = new JCheckBox("Also install Python " + req.pythonMin() + " and the sound-export package (runs your package manager)");
        JButton install = new JButton("Install");
        Runnable show = () -> {
            StringBuilder out = new StringBuilder("Minecraft folder: " + dir + "\n\n");
            InstallPlan.build(Detector.detect(dir, req), req, work, python.isSelected()).forEach(s -> out.append("- ").append(s.text()).append('\n'));
            text.setText(out.toString());
        };
        show.run();
        python.addActionListener(e -> show.run());
        install.addActionListener(e -> {
            install.setEnabled(false);
            Thread worker = new Thread(() -> {
                List<Step> steps = InstallPlan.build(Detector.detect(dir, req), req, work, python.isSelected());
                text.setText("");
                boolean done = new Installer(jar, line -> SwingUtilities.invokeLater(() -> text.append(line + "\n"))).run(steps);
                SwingUtilities.invokeLater(() -> {
                    text.append(done ? "\nDone. Start the NeoForge " + req.neoforgeVersion() + " profile.\n" : "\nThings are left to do (see above).\n");
                    install.setEnabled(true);
                });
            }, "craftpy-installer");
            worker.setDaemon(true);
            worker.start();
        });
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(python, BorderLayout.CENTER);
        bottom.add(install, BorderLayout.EAST);
        frame.add(new JScrollPane(text), BorderLayout.CENTER);
        frame.add(bottom, BorderLayout.SOUTH);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
}
