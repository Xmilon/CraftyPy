package net.xmilon.craftpy.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class PayloadTextTest {

    private static String roundTrip(String text, int max) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        PayloadText.write(buf, text, max);
        return PayloadText.read(buf, max);
    }

    @Test
    void shortLongAndUnicodeTextComeBackUnchanged() {
        assertEquals("", roundTrip("", 100));
        assertEquals("hi", roundTrip("hi", 100));
        String big = "net.minecraft.world.level.block.Block ✓ 🐍 ".repeat(60_000); // ~2.7 MB, far over the old 400k cap
        assertEquals(big, roundTrip(big, 8 * 1024 * 1024));
    }

    @Test
    void oversizedTextIsRefusedOnReadNotOnWrite() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        PayloadText.write(buf, "x".repeat(5000), 10_000);
        assertThrows(DecoderException.class, () -> PayloadText.read(buf, 1000));
    }

    @Test
    void oversizedReplyBecomesAFailureInsteadOfADisconnect() {
        ResponsePayload ok = ResponsePayload.success(ProjectAction.LIST, "", "[1]");
        assertTrue(ok.success());
        ResponsePayload huge = ResponsePayload.success(ProjectAction.LIST, "", "x".repeat(ResponsePayload.MAX_JSON_BYTES + 1));
        assertFalse(huge.success());
        assertEquals("", huge.json());
        assertTrue(huge.message().contains("too big"));
    }

    @Test
    void utf8LengthMatchesTheEncoder() {
        String text = "aé✓🐍";
        assertEquals(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, PayloadText.utf8Length(text));
    }
}
