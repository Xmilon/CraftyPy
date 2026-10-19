package net.xmilon.craftpy.client;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ModuleAcknowledgmentsTest {
    @TempDir Path root;
    @Test void choicesPersistPerServerModuleAndHashAndCanBeRevoked() {
        var file=root.resolve("choices.json"); var choices=new ModuleAcknowledgments(file);
        String a="a".repeat(64), b="b".repeat(64);
        choices.set("one.example:25565","jukebox",a,true);
        choices=new ModuleAcknowledgments(file);
        assertTrue(choices.remembered("one.example:25565","jukebox",a));
        assertFalse(choices.remembered("two.example:25565","jukebox",a));
        assertFalse(choices.remembered("one.example:25565","jukebox",b));
        choices.set("one.example:25565","other",b,true);
        assertTrue(choices.remembered("one.example:25565","jukebox",a));
        choices.set("one.example:25565","jukebox",a,false);
        assertFalse(new ModuleAcknowledgments(file).remembered("one.example:25565","jukebox",a));
        assertTrue(new ModuleAcknowledgments(file).remembered("one.example:25565","other",b));
        choices.set("one.example:25565","invalid","missing",true);
        assertFalse(choices.remembered("one.example:25565","invalid","missing"));
    }
}
