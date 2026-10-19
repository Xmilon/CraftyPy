package net.xmilon.craftpy.content;

import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeEntityStateTest {
    @Test void ownerValuesSurvivePersistenceAndNeverCrossScopes() {
        var data = new CompoundTag();
        var one = JsonParser.parseString("{\"mode\":\"guard\",\"energy\":40}").getAsJsonObject();
        var two = JsonParser.parseString("{\"mode\":\"follow\"}").getAsJsonObject();
        NativeEntityState.write(data, "module:one", one); NativeEntityState.write(data, "two", two);
        var saved = data.copy();
        assertEquals(one, NativeEntityState.read(saved, "module:one"));
        assertEquals(two, NativeEntityState.read(saved, "two"));
        assertTrue(NativeEntityState.read(saved, "unrelated").isEmpty());
        var tooLarge = new com.google.gson.JsonObject(); tooLarge.addProperty("text", "x".repeat(17000));
        assertThrows(IllegalArgumentException.class, () -> NativeEntityState.write(saved, "module:one", tooLarge));
        assertEquals(one, NativeEntityState.read(saved, "module:one"));
    }
}
