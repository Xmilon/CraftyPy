package net.xmilon.craftpy.server;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * Runs a Minecraft command and keeps what it answers ("Gave 3 [Diamond] to Alex", "Unknown command"), for the IDE
 * console's {@code /command} lines and Python's {@code run()}. Server thread only.
 */
public final class CommandOutput implements CommandSource {
    private final List<String> lines = new ArrayList<>();

    private CommandOutput() {
    }

    /** {@code source} decides who runs it (a player keeps their own permission level). */
    public static List<String> run(MinecraftServer server, CommandSourceStack source, String command) {
        CommandOutput output = new CommandOutput();
        String text = command.strip();
        if (text.startsWith("/")) {
            text = text.substring(1);
        }
        server.getCommands().performPrefixedCommand(source.withSource(output), text);
        return output.lines;
    }

    @Override
    public void sendSystemMessage(Component message) {
        lines.add(message.getString());
    }

    @Override
    public boolean acceptsSuccess() {
        return true;
    }

    @Override
    public boolean acceptsFailure() {
        return true;
    }

    @Override
    public boolean shouldInformAdmins() {
        return false;
    }
}
