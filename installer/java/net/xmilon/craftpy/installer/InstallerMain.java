package net.xmilon.craftpy.installer;

import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/**
 * Entry point. {@code --cli} (or no display) asks in the terminal; otherwise a small window shows the plan first.
 * Flags: {@code --dry-run} print the plan only, {@code --yes} no question, {@code --dir <minecraft folder>},
 * {@code --craftpy <jar>}, {@code --no-python} do not install CPython. Before anything runs the player is asked
 * whether CPython (shipped in the mod jar) is installed.
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
        Path work = Path.of(System.getProperty("java.io.tmpdir"), "craftpy-installer");
        if (a.contains("--cli") || GraphicsEnvironment.isHeadless()) {
            cli(a, dir, jar, req, work);
        } else {
            SwingUtilities.invokeLater(() -> window(dir, jar, req, work));
        }
    }

    /** The question asked before anything is installed. */
    static final String PYTHON_QUESTION = "CPython (the Python of the Python Software Foundation) is going to be installed:"
            + " CraftPy unpacks it into pyforge/system/python on its first start. Without it CraftPy needs a Python 3.13+"
            + " already on this computer. Install CPython?";

    private static Scanner input;

    /** One reader for every question (a second Scanner would lose what the first one buffered). */
    private static Scanner input() {
        if (input == null) {
            input = new Scanner(System.in);
        }
        return input;
    }

    private static void cli(List<String> a, Path dir, Path jar, Requirements req, Path work) {
        boolean python = !a.contains("--no-python");
        if (python && !a.contains("--yes") && !a.contains("--dry-run")) {
            System.out.print(PYTHON_QUESTION + " [Y/n] ");
            String answer = input().nextLine().trim();
            python = answer.isEmpty() || answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes");
        }
        List<Step> steps = InstallPlan.build(Detector.detect(dir, req), req, work, python);
        System.out.println("CraftPy installer, Minecraft folder: " + dir);
        steps.forEach(s -> System.out.println(" - " + s.text()));
        if (a.contains("--dry-run")) {
            return;
        }
        if (!a.contains("--yes")) {
            System.out.print("Run these steps? [y/N] ");
            if (!input().nextLine().trim().equalsIgnoreCase("y")) {
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
        JButton install = new JButton("Install");
        Runnable show = () -> {
            StringBuilder out = new StringBuilder("Minecraft folder: " + dir + "\n\n");
            InstallPlan.build(Detector.detect(dir, req), req, work).forEach(s -> out.append("- ").append(s.text()).append('\n'));
            text.setText(out.toString());
        };
        show.run();
        install.addActionListener(e -> {
            int answer = javax.swing.JOptionPane.showConfirmDialog(frame,
                    "<html><body style='width: 360px'>" + PYTHON_QUESTION + "</body></html>", "Install CPython?",
                    javax.swing.JOptionPane.YES_NO_CANCEL_OPTION, javax.swing.JOptionPane.QUESTION_MESSAGE);
            if (answer != javax.swing.JOptionPane.YES_OPTION && answer != javax.swing.JOptionPane.NO_OPTION) {
                return;
            }
            boolean python = answer == javax.swing.JOptionPane.YES_OPTION;
            install.setEnabled(false);
            Thread worker = new Thread(() -> {
                List<Step> steps = InstallPlan.build(Detector.detect(dir, req), req, work, python);
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
        bottom.add(new javax.swing.JLabel("CraftPy includes CPython; Install asks whether to use it."), BorderLayout.CENTER);
        bottom.add(install, BorderLayout.EAST);
        frame.add(new JScrollPane(text), BorderLayout.CENTER);
        frame.add(bottom, BorderLayout.SOUTH);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
}
