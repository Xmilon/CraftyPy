package net.xmilon.craftpy.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConsoleCommandTest {

    @Test
    void slashLinesAreCommandsEverythingElseIsPython() {
        assertEquals("/give @s diamond 3", ProjectRequestHandler.consoleCommand("{\"code\":\"  /give @s diamond 3 \",\"session\":\"default\"}"));
        assertEquals("/welcome:gift Dev 3", ProjectRequestHandler.consoleCommand("/welcome:gift Dev 3"));
        assertNull(ProjectRequestHandler.consoleCommand("{\"code\":\"print(1 / 2)\"}"));
        assertNull(ProjectRequestHandler.consoleCommand("{\"code\":\"/\"}"));
        assertNull(ProjectRequestHandler.consoleCommand("{\"code\":\"/give @s a\\nprint(1)\"}"));
    }
}
